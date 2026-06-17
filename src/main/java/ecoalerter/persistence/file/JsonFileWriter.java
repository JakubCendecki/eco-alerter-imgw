package ecoalerter.persistence.file;

import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Warning;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.AppLogger;
import ecoalerter.util.DateTimeUtil;
import ecoalerter.util.JsonParser;
import ecoalerter.util.PathResolver;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Zapisuje i odczytuje dane w formacie JSON.
 *
 * Każda stacja meteo i hydro ma własny plik JSON. Format pliku to tablica JSON
 * zawierająca obiekty pomiarów, posortowane chronologicznie.
 * Przy każdym zapisie plik jest wczytywany, nowe rekordy są dodawane i plik jest
 * nadpisywany — co zapewnia poprawny format (jedna tablica na plik).
 *
 * Pliki zapisywane są w podkatalogach: data/meteo/, data/hydro/, data/warnings/.
*/
public class JsonFileWriter {
    private static final Logger log = AppLogger.get(JsonFileWriter.class);

    private final PathResolver pathResolver;

    public JsonFileWriter(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * Dopisuje pomiar meteo do pliku JSON stacji.
     * Tworzy plik jeśli nie istnieje. Duplikaty (ta sama stacja + timestamp) są pomijane.
     *
     * @param data pomiar do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
    */
    public void writeMeteo(MeteoData data) throws PersistenceException {
        writeMeteoList(List.of(data));
    }

    /**
     * Dopisuje listę pomiarów meteo do odpowiednich plików JSON (grupuje po stacji).
     *
     * @param dataList lista pomiarów
     * @throws PersistenceException gdy zapis jakiegokolwiek pliku się nie powiedzie
    */
    public void writeMeteoList(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        // Grupuj po stacji — każda stacja ma osobny plik
        for (MeteoData data : dataList) {
            Path file = pathResolver.resolveMeteoFile(
                    data.getStationId(), data.getStationName(), "json");
            appendToJsonArray(file, data, MeteoData.class);
        }
    }

    /**
     * Odczytuje wszystkie pomiary meteo dla podanej stacji z pliku JSON.
     *
     * @param stationId   identyfikator stacji
     * @param stationName nazwa stacji (do budowania nazwy pliku)
     * @return lista pomiarów lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
    */
    public List<MeteoData> readMeteo(String stationId, String stationName)
            throws PersistenceException {
        Path file = pathResolver.resolveMeteoFile(stationId, stationName, "json");
        return readJsonArray(file, MeteoData.class);
    }

    /**
     * Dopisuje pomiar hydro do pliku JSON stacji.
     *
     * @param data pomiar do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
    */
    public void writeHydro(HydroData data) throws PersistenceException {
        writeHydroList(List.of(data));
    }

    /**
     * Dopisuje listę pomiarów hydro do odpowiednich plików JSON.
     *
     * @param dataList lista pomiarów
     * @throws PersistenceException gdy zapis jakiegokolwiek pliku się nie powiedzie
    */
    public void writeHydroList(List<HydroData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        for (HydroData data : dataList) {
            Path file = pathResolver.resolveHydroFile(
                    data.getStationId(), data.getStationName(), "json");
            appendToJsonArray(file, data, HydroData.class);
        }
    }

    /**
     * Odczytuje wszystkie pomiary hydro dla podanej stacji z pliku JSON.
     *
     * @param stationId   identyfikator stacji
     * @param stationName nazwa stacji
     * @return lista pomiarów lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
    */
    public List<HydroData> readHydro(String stationId, String stationName)
            throws PersistenceException {
        Path file = pathResolver.resolveHydroFile(stationId, stationName, "json");
        return readJsonArray(file, HydroData.class);
    }

    /**
     * Zapisuje listę ostrzeżeń do pliku JSON z bieżącą datą w nazwie.
     * Plik ostrzeżeń jest zawsze nadpisywany (nie dopisywany) — zawiera bieżący stan.
     *
     * @param warnings lista ostrzeżeń do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
    */
    public void writeWarnings(List<Warning> warnings) throws PersistenceException {
        if (warnings == null) warnings = List.of();

        Path file = pathResolver.resolveWarningsFile(DateTimeUtil.todayForFilename(), "json");

        try {
            String json = JsonParser.toPrettyJson(warnings);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Zapisano {} ostrzeżeń do: {}", warnings.size(), file.getFileName());
        } catch (IOException e) {
            throw new PersistenceException("Błąd zapisu ostrzeżeń do: " + file, e);
        }
    }

    /**
     * Odczytuje ostrzeżenia z pliku JSON dla podanej daty.
     *
     * @param date data w formacie yyyy-MM-dd
     * @return lista ostrzeżeń lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
    */
    public List<Warning> readWarnings(String date) throws PersistenceException {
        Path file = pathResolver.resolveWarningsFile(date, "json");
        return readJsonArray(file, Warning.class);
    }

    /**
     * Dopisuje jeden obiekt do tablicy JSON w pliku.
     * Wczytuje istniejącą tablicę, dodaje element i zapisuje z powrotem.
    */
    private <T> void appendToJsonArray(Path file, T item, Class<T> clazz)
            throws PersistenceException {
        List<T> existing = readJsonArray(file, clazz);
        existing.add(item);

        try {
            String json = JsonParser.toPrettyJson(existing);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Zaktualizowano plik JSON: {} ({} rekordów)", file.getFileName(), existing.size());
        } catch (IOException e) {
            throw new PersistenceException("Błąd zapisu do pliku JSON: " + file, e);
        }
    }

    /**
     * Odczytuje tablicę JSON z pliku i mapuje na listę obiektów.
     * Zwraca pustą, modyfikowalną listę gdy plik nie istnieje lub jest pusty.
    */
    private <T> List<T> readJsonArray(Path file, Class<T> clazz) throws PersistenceException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) return new ArrayList<>();
            return new ArrayList<>(JsonParser.fromJsonList(content, clazz));
        } catch (IOException e) {
            throw new PersistenceException("Błąd odczytu pliku JSON: " + file, e);
        }
    }
}