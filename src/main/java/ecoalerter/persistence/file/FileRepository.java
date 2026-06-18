package ecoalerter.persistence.file;

import ecoalerter.config.AppConfig;
import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.AppLogger;
import ecoalerter.util.JsonParser;
import ecoalerter.util.PathResolver;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Implementacja DataRepository zapisująca dane do plików JSON lub CSV.
 *
 * Format pliku jest sterowany przez storage.file.format w app.properties.
 * Obsługuje format JSON (przez JsonFileWriter) i CSV (przez CsvFileWriter).
 *
 * Stacje i ostrzeżenia są zawsze w formacie JSON niezależnie od wybranego formatu
 * danych pomiarowych, bo JSON lepiej nadaje się do struktur z opcjonalnymi polami.
 *
 * Metody filtrowania (findBy*, deleteOlderThan) są wykonywane w pamięci —
 * plik jest wczytywany, przefiltrowany i (w przypadku delete) zapisany z powrotem.
*/
public class FileRepository implements DataRepository {
    private static final Logger log = AppLogger.get(FileRepository.class);

    private static final String STATIONS_FILE = "stations.json";
    private static final String FORMAT_JSON   = "JSON";

    private final PathResolver   pathResolver;
    private final JsonFileWriter jsonWriter;
    private final CsvFileWriter  csvWriter;
    private final boolean        useJson;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public FileRepository(AppConfig config, PathResolver pathResolver) {
        this.pathResolver = pathResolver;
        this.jsonWriter   = new JsonFileWriter(pathResolver);
        this.csvWriter    = new CsvFileWriter(pathResolver);
        this.useJson      = FORMAT_JSON.equalsIgnoreCase(config.getStorageFileFormat());

        log.info("FileRepository uruchomiony [format={}]",
                useJson ? "JSON" : "CSV");
    }

    // =========================================================================
    // STACJE — zawsze JSON
    // =========================================================================

    @Override
    public void saveStation(Station station) throws PersistenceException {
        List<Station> all = findAllStations();

        // Upsert: usuń starą wersję jeśli istnieje, dodaj nową
        all.removeIf(s -> s.getId().equals(station.getId())
                       && s.getType() == station.getType());
        all.add(station);

        writeStationsFile(all);
        log.debug("Zapisano stację: {} [{}]", station.getId(), station.getType());
    }

    @Override
    public void deleteStation(String stationId, StationType type) throws PersistenceException {
        List<Station> all = findAllStations();
        int before = all.size();
        all.removeIf(s -> s.getId().equals(stationId) && s.getType() == type);
        writeStationsFile(all);
        log.info("Usunięto stację {} [{}], było {} stacji, jest {}",
                stationId, type, before, all.size());
    }

    @Override
    public List<Station> findAllStations() throws PersistenceException {
        Path file = pathResolver.getStationsConfigFile();
        if (!Files.exists(file)) return new ArrayList<>();

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) return new ArrayList<>();
            return new ArrayList<>(JsonParser.fromJsonList(json, Station.class));
        } catch (IOException e) {
            throw new PersistenceException("Błąd odczytu pliku stacji", e);
        }
    }

    @Override
    public List<Station> findActiveStations(StationType type) throws PersistenceException {
        return findAllStations().stream()
                .filter(s -> s.isActive() && s.getType() == type)
                .toList();
    }

    // =========================================================================
    // DANE METEOROLOGICZNE
    // =========================================================================

    @Override
    public void saveMeteo(MeteoData data) throws PersistenceException {
        // Wymuszamy spójną nazwę stacji na podstawie konfiguracji słownikowej
        String correctName = resolveStationName(data.getStationId(), StationType.METEO);
        data.setStationName(correctName);

        if (useJson) jsonWriter.writeMeteo(data);
        else          csvWriter.writeMeteo(data);
    }

    @Override
    public void saveAllMeteo(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;
        
        // Mapujemy poprawne nazwy dla całej listy
        for (MeteoData data : dataList) {
            String correctName = resolveStationName(data.getStationId(), StationType.METEO);
            data.setStationName(correctName);
        }

        if (useJson) jsonWriter.writeMeteoList(dataList);
        else          csvWriter.writeMeteoList(dataList);
        log.debug("Zapisano {} pomiarów meteo do plików", dataList.size());
    }

    @Override
    public List<MeteoData> findMeteoByStation(String stationId) throws PersistenceException {
        if (!useJson) {
            log.warn("findMeteoByStation — odczyt z CSV nie jest wspierany, zwracam pustą listę");
            return Collections.emptyList();
        }

        // Szukamy pliku — musimy znać nazwę stacji; próbujemy przez stacje zapisane
        String stationName = resolveStationName(stationId, StationType.METEO);
        List<MeteoData> all = jsonWriter.readMeteo(stationId, stationName);
        all.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp()); // malejąco
        });
        return all;
    }

    @Override
    public List<MeteoData> findMeteoByStationAndRange(String stationId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) throws PersistenceException {
        return findMeteoByStation(stationId).stream()
                .filter(d -> d.getTimestamp() != null
                          && !d.getTimestamp().isBefore(from)
                          && !d.getTimestamp().isAfter(to))
                .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .toList();
    }

    @Override
    public Optional<MeteoData> findLatestMeteo(String stationId) throws PersistenceException {
        List<MeteoData> all = findMeteoByStation(stationId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    @Override
    public int deleteMeteoOlderThan(LocalDateTime olderThan) throws PersistenceException {
        if (!useJson) {
            log.warn("deleteMeteoOlderThan — nie obsługiwane dla CSV");
            return 0;
        }

        List<Station> stations = findActiveStations(StationType.METEO);
        int total = 0;

        for (Station s : stations) {
            List<MeteoData> all  = jsonWriter.readMeteo(s.getId(), s.getName());
            int             before = all.size();
            all.removeIf(d -> d.getTimestamp() != null && d.getTimestamp().isBefore(olderThan));
            int removed = before - all.size();

            if (removed > 0) {
                Path file = pathResolver.resolveMeteoFile(s.getId(), s.getName(), "json");
                rewriteJsonFile(file, all);
                total += removed;
            }
        }

        log.info("Usunięto {} starych rekordów meteo (przed {})", total, olderThan);
        return total;
    }

    // =========================================================================
    // DANE HYDROLOGICZNE
    // =========================================================================

    @Override
    public void saveHydro(HydroData data) throws PersistenceException {
        // Wymuszamy spójną nazwę stacji dla danych hydro
        String correctName = resolveStationName(data.getStationId(), StationType.HYDRO);
        data.setStationName(correctName);

        if (useJson) jsonWriter.writeHydro(data);
        else          csvWriter.writeHydro(data);
    }

    @Override
    public void saveAllHydro(List<HydroData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        // Mapujemy poprawne nazwy dla całej listy hydro
        for (HydroData data : dataList) {
            String correctName = resolveStationName(data.getStationId(), StationType.HYDRO);
            data.setStationName(correctName);
        }

        if (useJson) jsonWriter.writeHydroList(dataList);
        else          csvWriter.writeHydroList(dataList);
        log.debug("Zapisano {} pomiarów hydro do plików", dataList.size());
    }

    @Override
    public List<HydroData> findHydroByStation(String stationId) throws PersistenceException {
        if (!useJson) {
            log.warn("findHydroByStation — odczyt z CSV nie jest wspierany, zwracam pustą listę");
            return Collections.emptyList();
        }

        String stationName = resolveStationName(stationId, StationType.HYDRO);
        List<HydroData> all = jsonWriter.readHydro(stationId, stationName);
        all.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });
        return all;
    }

    @Override
    public List<HydroData> findHydroByStationAndRange(String stationId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) throws PersistenceException {
        return findHydroByStation(stationId).stream()
                .filter(d -> d.getTimestamp() != null
                          && !d.getTimestamp().isBefore(from)
                          && !d.getTimestamp().isAfter(to))
                .sorted((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()))
                .toList();
    }

    @Override
    public Optional<HydroData> findLatestHydro(String stationId) throws PersistenceException {
        List<HydroData> all = findHydroByStation(stationId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    @Override
    public int deleteHydroOlderThan(LocalDateTime olderThan) throws PersistenceException {
        if (!useJson) {
            log.warn("deleteHydroOlderThan — nie obsługiwane dla CSV");
            return 0;
        }

        List<Station> stations = findActiveStations(StationType.HYDRO);
        int total = 0;

        for (Station s : stations) {
            List<HydroData> all    = jsonWriter.readHydro(s.getId(), s.getName());
            int             before = all.size();
            all.removeIf(d -> d.getTimestamp() != null && d.getTimestamp().isBefore(olderThan));
            int removed = before - all.size();

            if (removed > 0) {
                Path file = pathResolver.resolveHydroFile(s.getId(), s.getName(), "json");
                rewriteJsonFile(file, all);
                total += removed;
            }
        }

        log.info("Usunięto {} starych rekordów hydro (przed {})", total, olderThan);
        return total;
    }

    // =========================================================================
    // OSTRZEŻENIA — zawsze JSON
    // =========================================================================

    @Override
    public void saveWarning(Warning warning) throws PersistenceException {
        saveAllWarnings(List.of(warning));
    }

    @Override
    public void saveAllWarnings(List<Warning> warnings) throws PersistenceException {
        if (useJson) jsonWriter.writeWarnings(warnings);
        else         csvWriter.writeWarnings(warnings);
    }

    @Override
    public List<Warning> findActiveWarnings() throws PersistenceException {
        if (!useJson) return Collections.emptyList();

        List<Warning> today = jsonWriter.readWarnings(
                java.time.LocalDate.now().toString());
        return today.stream().filter(Warning::isActive).toList();
    }

    @Override
    public List<Warning> findActiveWarningsByMinLevel(WarningLevel minLevel)
            throws PersistenceException {
        return findActiveWarnings().stream()
                .filter(w -> w.meetsLevel(minLevel))
                .toList();
    }

    @Override
    public int deleteExpiredWarnings() throws PersistenceException {
        // Ostrzeżenia są nadpisywane przy każdym cyklu — brak potrzeby czyszczenia
        log.debug("deleteExpiredWarnings — brak akcji w trybie FILE (nadpisywanie przy odświeżeniu)");
        return 0;
    }

    // =========================================================================
    // ZARZĄDZANIE ZASOBAMI
    // =========================================================================

    @Override
    public void close() {
        log.info("FileRepository zamknięty");
    }

    // =========================================================================
    // METODY POMOCNICZE
    // =========================================================================

    private void writeStationsFile(List<Station> stations) throws PersistenceException {
        Path file = pathResolver.getStationsConfigFile();
        try {
            String json = JsonParser.toPrettyJson(stations);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new PersistenceException("Błąd zapisu pliku stacji", e);
        }
    }

    private void rewriteJsonFile(Path file, List<?> data) throws PersistenceException {
        try {
            String json = JsonParser.toPrettyJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new PersistenceException("Błąd nadpisywania pliku: " + file, e);
        }
    }

    private String resolveStationName(String stationId, StationType type) {
        try {
            return findAllStations().stream()
                    .filter(s -> s.getId().equals(stationId) && s.getType() == type)
                    .map(Station::getName)
                    .findFirst()
                    .orElse("");
        } catch (PersistenceException e) {
            log.debug("Nie można rozwiązać nazwy stacji {}: {}", stationId, e.getMessage());
            return "";
        }
    }
}