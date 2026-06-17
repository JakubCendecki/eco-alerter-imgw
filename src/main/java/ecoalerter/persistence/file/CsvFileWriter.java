package ecoalerter.persistence.file;

import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Warning;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.AppLogger;
import ecoalerter.util.DateTimeUtil;
import ecoalerter.util.PathResolver;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Zapisuje dane pomiarowe do plików CSV.
 *
 * Każda stacja ma swój plik CSV z nagłówkiem w pierwszej linii.
 * Nagłówek jest zapisywany tylko przy tworzeniu nowego pliku.
 * Nowe pomiary są dopisywane na końcu pliku.
 *
 * Wartości null są reprezentowane jako puste pole (dwa sąsiadujące przecinki).
 * Pola tekstowe zawierające przecinki są otaczane cudzysłowem.
 *
 * Separatorem kolumn jest przecinek. Separator dziesiętny to kropka.
*/
public class CsvFileWriter {
    private static final Logger log = AppLogger.get(CsvFileWriter.class);

    private static final char   SEPARATOR = ',';
    private static final String LINE_SEP  = System.lineSeparator();

    // Nagłówki kolumn
    static final String METEO_HEADER =
            "station_id,timestamp,temperature_c,wind_speed_ms,precipitation_mm,pressure_hpa";
    static final String HYDRO_HEADER =
            "station_id,timestamp,water_level_cm,water_temperature_c,flow_m3s,ice_phenomenon,overgrowth_phenomenon";
    static final String WARNINGS_HEADER =
            "id,station_id,level,type,phenomenon,probability,message,issued_at,valid_until";

    private final PathResolver pathResolver;

    public CsvFileWriter(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * Dopisuje pomiar meteo do pliku CSV stacji.
     * Tworzy plik z nagłówkiem jeśli nie istnieje.
     *
     * @param data pomiar do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
    */
    public void writeMeteo(MeteoData data) throws PersistenceException {
        writeMeteoList(List.of(data));
    }

    /**
     * Dopisuje listę pomiarów meteo do odpowiednich plików CSV (grupuje po stacji).
     *
     * @param dataList lista pomiarów
     * @throws PersistenceException gdy zapis jakiegokolwiek pliku się nie powiedzie
    */
    public void writeMeteoList(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        for (MeteoData data : dataList) {
            Path file = pathResolver.resolveMeteoFile(
                    data.getStationId(), data.getStationName(), "csv");
            appendLine(file, METEO_HEADER, buildMeteoLine(data));
        }
    }

    /**
     * Dopisuje pomiar hydro do pliku CSV stacji.
     * Tworzy plik z nagłówkiem jeśli nie istnieje.
     *
     * @param data pomiar do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
    */
    public void writeHydro(HydroData data) throws PersistenceException {
        writeHydroList(List.of(data));
    }

    /**
     * Dopisuje listę pomiarów hydro do odpowiednich plików CSV.
     *
     * @param dataList lista pomiarów
     * @throws PersistenceException gdy zapis jakiegokolwiek pliku się nie powiedzie
    */
    public void writeHydroList(List<HydroData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        for (HydroData data : dataList) {
            Path file = pathResolver.resolveHydroFile(
                    data.getStationId(), data.getStationName(), "csv");
            appendLine(file, HYDRO_HEADER, buildHydroLine(data));
        }
    }

    /**
     * Zapisuje listę ostrzeżeń do pliku CSV z bieżącą datą w nazwie.
     * Plik jest zawsze nadpisywany (zawiera bieżący stan ostrzeżeń).
     *
     * @param warnings lista ostrzeżeń do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
    */
    public void writeWarnings(List<Warning> warnings) throws PersistenceException {
        Path file = pathResolver.resolveWarningsFile(DateTimeUtil.todayForFilename(), "csv");

        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            writer.println(WARNINGS_HEADER);
            if (warnings != null) {
                for (Warning w : warnings) {
                    writer.println(buildWarningLine(w));
                }
            }
            log.debug("Zapisano {} ostrzeżeń do CSV: {}", warnings == null ? 0 : warnings.size(),
                    file.getFileName());

        } catch (IOException e) {
            throw new PersistenceException("Błąd zapisu ostrzeżeń CSV: " + file, e);
        }
    }

    // -------------------------------------------------------------------------
    // Budowanie linii CSV
    // -------------------------------------------------------------------------

    private String buildMeteoLine(MeteoData d) {
        return join(
                escape(d.getStationId()),
                escape(DateTimeUtil.toDbString(d.getTimestamp())),
                formatDouble(d.getTemperature()),
                formatDouble(d.getWindSpeed()),
                formatDouble(d.getPrecipitation()),
                formatDouble(d.getPressure())
        );
    }

    private String buildHydroLine(HydroData d) {
        return join(
                escape(d.getStationId()),
                escape(DateTimeUtil.toDbString(d.getTimestamp())),
                formatDouble(d.getWaterLevel()),
                formatDouble(d.getWaterTemperature()),
                formatDouble(d.getFlow()),
                String.valueOf(d.getIcePhenomenon()),
                String.valueOf(d.getOvergrowthPhenomenon())
        );
    }

    private String buildWarningLine(Warning w) {
        return join(
                escape(w.getId()),
                escape(w.getStationId()),
                escape(w.getLevel()  != null ? w.getLevel().name() : ""),
                escape(w.getType()   != null ? w.getType().name()  : ""),
                escape(w.getPhenomenon()),
                String.valueOf(w.getProbability()),
                escape(w.getMessage()),
                escape(DateTimeUtil.toDbString(w.getIssuedAt())),
                escape(DateTimeUtil.toDbString(w.getValidUntil()))
        );
    }

    /** Dopisuje wiersz do pliku CSV. Jeśli plik nie istnieje, zapisuje najpierw nagłówek. */
    private void appendLine(Path file, String header, String line) throws PersistenceException {
        try {
            boolean isNew = !Files.exists(file);

            if (isNew) {
                Files.writeString(file, header + LINE_SEP, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
                log.debug("Utworzono nowy plik CSV z nagłówkiem: {}", file.getFileName());
            }

            Files.writeString(file, line + LINE_SEP, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);

        } catch (IOException e) {
            throw new PersistenceException("Błąd dopisywania do pliku CSV: " + file, e);
        }
    }

    /** Łączy wartości separatorem przecinka. */
    private String join(String... values) {
        return String.join(String.valueOf(SEPARATOR), values);
    }

    /** Formatuje Double do stringa bez zbędnych zer lub zwraca pusty string dla null. */
    private String formatDouble(Double value) {
        if (value == null) return "";
        // Jeśli wartość jest całkowita — nie drukuj '.0'
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    /**
     * Zabezpiecza pole tekstowe do formatu CSV:
     * otacza cudzysłowem jeśli zawiera przecinek, cudzysłów lub znak nowej linii.
     * Null jest zwracany jako pusty string.
    */
    private String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}