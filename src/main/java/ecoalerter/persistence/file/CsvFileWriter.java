package ecoalerter.persistence.file;

import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Zapisuje i odczytuje dane pomiarowe oraz ostrzeżenia w formacie CSV.
 *
 * Każda stacja ma swój plik CSV z nagłówkiem w pierwszej linii.
 * Nagłówek jest zapisywany tylko przy tworzeniu nowego pliku.
 * Nowe pomiary są dopisywane na końcu pliku.
 *
 * Wartości null są reprezentowane jako puste pole (dwa sąsiadujące przecinki).
 * Pola tekstowe zawierające przecinki, cudzysłowy lub znaki nowej linii są
 * otaczane cudzysłowem zgodnie z RFC4180; znak nowej linii w treści ostrzeżenia
 * jest dodatkowo zamieniany na spację przed zapisem, żeby każdy rekord
 * pozostawał w jednej fizycznej linii pliku — to znacznie upraszcza odczyt
 * (parsowanie linia-po-linii) bez utraty istotnej treści komunikatu.
 *
 * Separatorem kolumn jest przecinek. Separator dziesiętny to kropka.
 */
public class CsvFileWriter {

    private static final Logger log = AppLogger.get(CsvFileWriter.class);

    private static final char   SEPARATOR = ',';
    private static final String LINE_SEP  = System.lineSeparator();

    // Nagłówki kolumn
    static final String METEO_HEADER =
            "station_id,timestamp,temperature_c,wind_speed_ms,precipitation_mm";
    static final String HYDRO_HEADER =
            "station_id,timestamp,water_level_cm,water_temperature_c,flow_m3s,ice_phenomenon,overgrowth_phenomenon";
    static final String WARNINGS_HEADER =
            "id,station_id,level,type,phenomenon,probability,message,issued_at,valid_until";

    private final PathResolver pathResolver;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public CsvFileWriter(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    // =========================================================================
    // ZAPIS — DANE METEO
    // =========================================================================

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

    // =========================================================================
    // ODCZYT — DANE METEO
    // =========================================================================

    /**
     * Odczytuje wszystkie pomiary meteo dla podanej stacji z pliku CSV.
     * Wiersze z błędnym formatem (np. uszkodzona linia) są pomijane z logiem
     * ostrzeżenia, nie przerywają odczytu pozostałych.
     *
     * @param stationId   identyfikator stacji
     * @param stationName nazwa stacji (do budowania nazwy pliku)
     * @return lista pomiarów lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
     */
    public List<MeteoData> readMeteo(String stationId, String stationName)
            throws PersistenceException {
        Path file = pathResolver.resolveMeteoFile(stationId, stationName, "csv");
        List<String> lines = readDataLines(file);

        List<MeteoData> result = new ArrayList<>();
        for (String line : lines) {
            try {
                result.add(parseMeteoLine(line));
            } catch (Exception e) {
                log.warn("Pominięto nieprawidłowy wiersz CSV meteo w {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Nadpisuje cały plik CSV stacji meteo podaną listą pomiarów (z nagłówkiem).
     * Używane przy czyszczeniu starych danych (deleteMeteoOlderThan) — w
     * przeciwieństwie do writeMeteo, nie dopisuje, a zastępuje całą zawartość.
     *
     * @param stationId   identyfikator stacji
     * @param stationName nazwa stacji
     * @param dataList    pełna lista pomiarów do zapisania
     * @throws PersistenceException gdy zapis się nie powiedzie
     */
    public void rewriteMeteo(String stationId, String stationName, List<MeteoData> dataList)
            throws PersistenceException {
        Path file = pathResolver.resolveMeteoFile(stationId, stationName, "csv");
        List<String> lines = dataList.stream().map(this::buildMeteoLine).toList();
        rewriteFile(file, METEO_HEADER, lines);
    }

    // =========================================================================
    // ZAPIS — DANE HYDRO
    // =========================================================================

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

    // =========================================================================
    // ODCZYT — DANE HYDRO
    // =========================================================================

    /**
     * Odczytuje wszystkie pomiary hydro dla podanej stacji z pliku CSV.
     *
     * @param stationId   identyfikator stacji
     * @param stationName nazwa stacji
     * @return lista pomiarów lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
     */
    public List<HydroData> readHydro(String stationId, String stationName)
            throws PersistenceException {
        Path file = pathResolver.resolveHydroFile(stationId, stationName, "csv");
        List<String> lines = readDataLines(file);

        List<HydroData> result = new ArrayList<>();
        for (String line : lines) {
            try {
                result.add(parseHydroLine(line));
            } catch (Exception e) {
                log.warn("Pominięto nieprawidłowy wiersz CSV hydro w {}: {}", file.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Nadpisuje cały plik CSV stacji hydro podaną listą pomiarów (z nagłówkiem).
     * Używane przy czyszczeniu starych danych (deleteHydroOlderThan).
     *
     * @param stationId   identyfikator stacji
     * @param stationName nazwa stacji
     * @param dataList    pełna lista pomiarów do zapisania
     * @throws PersistenceException gdy zapis się nie powiedzie
     */
    public void rewriteHydro(String stationId, String stationName, List<HydroData> dataList)
            throws PersistenceException {
        Path file = pathResolver.resolveHydroFile(stationId, stationName, "csv");
        List<String> lines = dataList.stream().map(this::buildHydroLine).toList();
        rewriteFile(file, HYDRO_HEADER, lines);
    }

    // =========================================================================
    // ZAPIS / ODCZYT — OSTRZEŻENIA
    // =========================================================================

    /**
     * Zapisuje listę ostrzeżeń do pliku CSV z bieżącą datą w nazwie.
     * Plik jest zawsze nadpisywany (zawiera bieżący stan ostrzeżeń, nie historię).
     *
     * @param warnings lista ostrzeżeń do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
     */
    public void writeWarnings(List<Warning> warnings) throws PersistenceException {
        Path file = pathResolver.resolveWarningsFile(DateTimeUtil.todayForFilename(), "csv");
        List<String> lines = warnings == null
                ? List.of()
                : warnings.stream().map(this::buildWarningLine).toList();
        rewriteFile(file, WARNINGS_HEADER, lines);
        log.debug("Zapisano {} ostrzeżeń do CSV: {}", lines.size(), file.getFileName());
    }

    /**
     * Odczytuje ostrzeżenia z pliku CSV dla podanej daty.
     *
     * @param date data w formacie yyyy-MM-dd
     * @return lista ostrzeżeń lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
     */
    public List<Warning> readWarnings(String date) throws PersistenceException {
        Path file = pathResolver.resolveWarningsFile(date, "csv");
        List<String> lines = readDataLines(file);

        List<Warning> result = new ArrayList<>();
        for (String line : lines) {
            try {
                result.add(parseWarningLine(line));
            } catch (Exception e) {
                log.warn("Pominięto nieprawidłowy wiersz CSV ostrzeżenia w {}: {}",
                        file.getFileName(), e.getMessage());
            }
        }
        return result;
    }

    // =========================================================================
    // BUDOWANIE LINII CSV (ZAPIS)
    // =========================================================================

    private String buildMeteoLine(MeteoData d) {
        return join(
                escape(d.getStationId()),
                escape(DateTimeUtil.toDbString(d.getTimestamp())),
                formatDouble(d.getTemperature()),
                formatDouble(d.getWindSpeed()),
                formatDouble(d.getPrecipitation())
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

    // =========================================================================
    // PARSOWANIE LINII CSV (ODCZYT)
    // =========================================================================

    private MeteoData parseMeteoLine(String line) {
        List<String> f = splitCsvLine(line, 5);

        MeteoData d = new MeteoData();
        d.setStationId(unescape(f.get(0)));
        d.setTimestamp(DateTimeUtil.parse(unescape(f.get(1))).orElse(null));
        d.setTemperature(parseNullableDouble(f.get(2)));
        d.setWindSpeed(parseNullableDouble(f.get(3)));
        d.setPrecipitation(parseNullableDouble(f.get(4)));
        return d;
    }

    private HydroData parseHydroLine(String line) {
        List<String> f = splitCsvLine(line, 7);

        HydroData d = new HydroData();
        d.setStationId(unescape(f.get(0)));
        d.setTimestamp(DateTimeUtil.parse(unescape(f.get(1))).orElse(null));
        d.setWaterLevel(parseNullableDouble(f.get(2)));
        d.setWaterTemperature(parseNullableDouble(f.get(3)));
        d.setFlow(parseNullableDouble(f.get(4)));
        d.setIcePhenomenon(parseNullableInt(f.get(5), 0));
        d.setOvergrowthPhenomenon(parseNullableInt(f.get(6), 0));
        return d;
    }

    private Warning parseWarningLine(String line) {
        List<String> f = splitCsvLine(line, 9);

        Warning w = new Warning();
        w.setId(unescape(f.get(0)));
        w.setStationId(blankToNull(unescape(f.get(1))));
        w.setLevel(blankToNull(unescape(f.get(2))) != null
                ? WarningLevel.fromString(unescape(f.get(2))) : null);
        w.setType(blankToNull(unescape(f.get(3))) != null
                ? StationType.fromString(unescape(f.get(3))) : null);
        w.setPhenomenon(blankToNull(unescape(f.get(4))));
        w.setProbability(parseNullableInt(f.get(5), -1));
        w.setMessage(blankToNull(unescape(f.get(6))));
        w.setIssuedAt(DateTimeUtil.parse(unescape(f.get(7))).orElse(null));
        w.setValidUntil(DateTimeUtil.parse(unescape(f.get(8))).orElse(null));
        return w;
    }

    // =========================================================================
    // METODY POMOCNICZE — I/O PLIKÓW
    // =========================================================================

    /**
     * Dopisuje wiersz do pliku CSV. Jeśli plik nie istnieje, zapisuje najpierw nagłówek.
     */
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

    /**
     * Nadpisuje cały plik CSV nagłówkiem i podanymi liniami danych.
     */
    private void rewriteFile(Path file, String header, List<String> dataLines)
            throws PersistenceException {
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            writer.println(header);
            for (String line : dataLines) {
                writer.println(line);
            }

        } catch (IOException e) {
            throw new PersistenceException("Błąd nadpisywania pliku CSV: " + file, e);
        }
    }

    /**
     * Wczytuje wszystkie linie pliku CSV poza nagłówkiem (pierwszą linią)
     * i poza liniami całkowicie pustymi. Zwraca pustą listę gdy plik nie istnieje.
     */
    private List<String> readDataLines(Path file) throws PersistenceException {
        if (!Files.exists(file)) return new ArrayList<>();

        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (all.isEmpty()) return new ArrayList<>();

            List<String> data = new ArrayList<>();
            for (int i = 1; i < all.size(); i++) { // od 1 — pomijamy nagłówek
                String line = all.get(i);
                if (!line.isBlank()) data.add(line);
            }
            return data;

        } catch (IOException e) {
            throw new PersistenceException("Błąd odczytu pliku CSV: " + file, e);
        }
    }

    // =========================================================================
    // METODY POMOCNICZE — FORMATOWANIE WARTOŚCI
    // =========================================================================

    private String join(String... values) {
        return String.join(String.valueOf(SEPARATOR), values);
    }

    /**
     * Formatuje Double do stringa bez zbędnych zer lub zwraca pusty string dla null.
     */
    private String formatDouble(Double value) {
        if (value == null) return "";
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private Double parseNullableDouble(String raw) {
        String v = unescape(raw).trim();
        if (v.isEmpty()) return null;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseNullableInt(String raw, int defaultValue) {
        String v = unescape(raw).trim();
        if (v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // =========================================================================
    // METODY POMOCNICZE — ESCAPING / PARSOWANIE CSV (RFC4180, jedna linia = jeden rekord)
    // =========================================================================

    /**
     * Zabezpiecza pole tekstowe do formatu CSV: otacza cudzysłowem jeśli
     * zawiera przecinek lub cudzysłów, i zamienia znaki nowej linii na spację —
     * żeby każdy rekord zawsze pozostawał w jednej fizycznej linii pliku
     * (treść ostrzeżeń IMGW bywa wieloliniowa, np. pole "przebieg").
     * Null jest zwracany jako pusty string.
     */
    private String escape(String value) {
        if (value == null) return "";

        String sanitized = value.replace("\r\n", " ")
                                .replace("\n", " ")
                                .replace("\r", " ");

        if (sanitized.contains(",") || sanitized.contains("\"")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }

    /**
     * Usuwa otaczające cudzysłowy i odwraca podwojenie cudzysłowów
     * dodane przez escape(). Pole bez cudzysłowów jest zwracane bez zmian.
     */
    private String unescape(String field) {
        if (field == null) return "";
        String trimmed = field.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed;
    }

    /**
     * Dzieli jedną linię CSV na pola, respektując cudzysłowy — przecinek
     * wewnątrz cudzysłowu nie jest traktowany jako separator.
     * Zwracane pola wciąż zawierają otaczające cudzysłowy (jeśli były) —
     * właściwe odescapowanie robi unescape() przy odczycie konkretnej wartości.
     *
     * @param line          linia do podzielenia
     * @param expectedCount oczekiwana liczba kolumn — w razie niedopasowania
     *                      rzuca IllegalArgumentException, żeby wywołujący
     *                      mógł zalogować i pominąć uszkodzony wiersz
     */
    private List<String> splitCsvLine(String line, int expectedCount) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    boolean nextIsQuote = i + 1 < line.length() && line.charAt(i + 1) == '"';
                    if (nextIsQuote) {
                        current.append("\"\"");
                        i++; // pomiń drugi cudzysłów — już dodany jako para
                    } else {
                        current.append('"');
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    current.append('"');
                    inQuotes = true;
                } else if (c == SEPARATOR) {
                    fields.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());

        if (fields.size() != expectedCount) {
            throw new IllegalArgumentException(
                    "Nieprawidłowa liczba kolumn: oczekiwano " + expectedCount
                    + ", znaleziono " + fields.size());
        }

        return fields;
    }
}