package ecoalerter.persistence.file;

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
import java.util.List;
import java.util.Optional;

/**
 * Implementacja DataRepository zapisująca dane do plików JSON.
 *
 * Metody filtrowania (findBy*, deleteOlderThan) są wykonywane w pamięci —
 * plik jest wczytywany, przefiltrowany i (w przypadku delete) zapisany z powrotem.
 */
public class FileRepository implements DataRepository {

    private static final Logger log = AppLogger.get(FileRepository.class);

    private final PathResolver   pathResolver;
    private final JsonFileWriter jsonWriter;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    /**
     * @param pathResolver komponent rozwiązujący ścieżki na dysku
     *                     (data/stations.json, data/meteo/&lt;id&gt;.json itd.)
     */
    public FileRepository(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
        this.jsonWriter   = new JsonFileWriter(pathResolver);

        log.info("FileRepository uruchomiony [format=JSON]");
    }

    // =========================================================================
    // STACJE
    // =========================================================================

    /**
     * Zapisuje stację do pliku stations.json — operacja upsert: jeśli stacja
     * o tym samym ID i typie już istnieje, zostaje zastąpiona; w przeciwnym
     * razie jest dopisywana. Sprawdzanie duplikatów na poziomie biznesowym
     * (czy w ogóle wolno zapisać) odbywa się w {@link ecoalerter.service.StationService}.
     */
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

    /**
     * Usuwa stację ze stations.json i kasuje jej powiązany plik danych
     * pomiarowych (meteo lub hydro). Bez kasowania pliku danych, ponowne
     * dodanie stacji o tym samym ID pokazywałoby historię sprzed usunięcia.
     *
     * Pliki ostrzeżeń nie są dotykane — są wspólne dla wszystkich stacji
     * i nie wiążą się z pojedynczą stacją.
     */
    @Override
    public void deleteStation(String stationId, StationType type) throws PersistenceException {
        List<Station> all = findAllStations();

        // Najpierw znajdź stację — jej nazwa będzie potrzebna do skasowania
        // odpowiednich plików z danymi pomiarowymi (PathResolver buduje nazwę
        // pliku z id + name).
        Optional<Station> toDelete = all.stream()
                .filter(s -> s.getId().equals(stationId) && s.getType() == type)
                .findFirst();

        int before = all.size();
        all.removeIf(s -> s.getId().equals(stationId) && s.getType() == type);
        writeStationsFile(all);

        // Skasuj powiązane pliki z danymi (meteo/hydro) — bez tego ponowne
        // dodanie stacji o tym samym ID pokazywałoby historię sprzed usunięcia.
        toDelete.ifPresent(this::deleteStationDataFiles);

        log.info("Usunięto stację {} [{}], było {} stacji, jest {}",
                stationId, type, before, all.size());
    }

    /**
     * Usuwa plik(i) z danymi pomiarowymi danej stacji.
     * Niepowodzenie logowane jako warning — usunięcie samej stacji już się powiodło,
     * więc nie chcemy całej operacji wycofywać przez wyjątek.
     */
    private void deleteStationDataFiles(Station station) {
        Path dataFile = (station.getType() == StationType.METEO)
                ? pathResolver.resolveMeteoFile(station.getId(), station.getName(), "json")
                : pathResolver.resolveHydroFile(station.getId(), station.getName(), "json");

        try {
            if (Files.deleteIfExists(dataFile)) {
                log.info("Usunięto plik danych: {}", dataFile);
            }
        } catch (IOException e) {
            log.warn("Nie udało się usunąć pliku danych dla stacji {} ({}): {}",
                    station.getId(), dataFile, e.getMessage());
        }
    }

    /**
     * Czyta wszystkie stacje z pliku stations.json. Zwraca pustą,
     * modyfikowalną listę gdy plik nie istnieje lub jest pusty.
     */
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

    /**
     * Zwraca aktywne stacje podanego typu — filtruje wynik
     * {@link #findAllStations()} po polu {@code active} i polu {@code type}.
     */
    @Override
    public List<Station> findActiveStations(StationType type) throws PersistenceException {
        return findAllStations().stream()
                .filter(s -> s.isActive() && s.getType() == type)
                .toList();
    }

    // =========================================================================
    // DANE METEOROLOGICZNE
    // =========================================================================

    /** Zapisuje pojedynczy pomiar meteo do pliku JSON stacji. */
    @Override
    public void saveMeteo(MeteoData data) throws PersistenceException {
        jsonWriter.writeMeteo(data);
    }

    /**
     * Zapisuje listę pomiarów meteo — grupowanie po stacji odbywa się
     * w {@link JsonFileWriter#writeMeteoList(List)}.
     */
    @Override
    public void saveAllMeteo(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;
        jsonWriter.writeMeteoList(dataList);
        log.debug("Zapisano {} pomiarów meteo do plików", dataList.size());
    }

    /**
     * Wszystkie pomiary meteo dla stacji, posortowane malejąco po czasie
     * (najnowsze pierwsze).
     */
    @Override
    public List<MeteoData> findMeteoByStation(String stationId) throws PersistenceException {
        List<MeteoData> all = jsonWriter.readMeteo(stationId, "");
        all.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp()); // malejąco
        });
        return all;
    }

    /**
     * Pomiary meteo z zakresu [from, to] (inclusive), posortowane rosnąco
     * po czasie — wygodne dla wykresów i tabel czytanych od najstarszych.
     */
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

    /** Najnowszy pomiar meteo dla stacji albo empty gdy brak historii. */
    @Override
    public Optional<MeteoData> findLatestMeteo(String stationId) throws PersistenceException {
        List<MeteoData> all = findMeteoByStation(stationId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Usuwa rekordy meteo starsze niż {@code olderThan} ze wszystkich plików
     * aktywnych stacji meteo.
     *
     * @param olderThan próg czasowy — rekordy ściśle przed nim są usuwane
     * @return łączna liczba usuniętych rekordów ze wszystkich plików
     */
    @Override
    public int deleteMeteoOlderThan(LocalDateTime olderThan) throws PersistenceException {
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

    /** Zapisuje pojedynczy pomiar hydro do pliku JSON stacji. */
    @Override
    public void saveHydro(HydroData data) throws PersistenceException {
        jsonWriter.writeHydro(data);
    }

    /** Zapisuje listę pomiarów hydro — grupowanie po stacji w JsonFileWriter. */
    @Override
    public void saveAllHydro(List<HydroData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;
        jsonWriter.writeHydroList(dataList);
        log.debug("Zapisano {} pomiarów hydro do plików", dataList.size());
    }

    /** Wszystkie pomiary hydro dla stacji, posortowane malejąco (najnowsze pierwsze). */
    @Override
    public List<HydroData> findHydroByStation(String stationId) throws PersistenceException {
        List<HydroData> all = jsonWriter.readHydro(stationId, "");
        all.sort((a, b) -> {
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });
        return all;
    }

    /** Pomiary hydro z zakresu [from, to], posortowane rosnąco po czasie. */
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

    /** Najnowszy pomiar hydro dla stacji albo empty gdy brak historii. */
    @Override
    public Optional<HydroData> findLatestHydro(String stationId) throws PersistenceException {
        List<HydroData> all = findHydroByStation(stationId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Usuwa rekordy hydro starsze niż {@code olderThan}.
     *
     * @param olderThan próg czasowy — rekordy ściśle przed nim są usuwane
     * @return łączna liczba usuniętych rekordów ze wszystkich plików
     */
    @Override
    public int deleteHydroOlderThan(LocalDateTime olderThan) throws PersistenceException {
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
    // OSTRZEŻENIA
    // =========================================================================

    /** Zapisuje pojedyncze ostrzeżenie — opakowuje w listę jednoelementową. */
    @Override
    public void saveWarning(Warning warning) throws PersistenceException {
        saveAllWarnings(List.of(warning));
    }

    /**
     * Zapisuje listę ostrzeżeń — w pliku z bieżącą datą w nazwie. Plik
     * ostrzeżeń jest zawsze nadpisywany (nie dopisywany) — zawiera bieżący stan.
     */
    @Override
    public void saveAllWarnings(List<Warning> warnings) throws PersistenceException {
        jsonWriter.writeWarnings(warnings);
    }

    /**
     * Zwraca aktywne ostrzeżenia z dzisiejszego pliku ostrzeżeń.
     * Filtrowanie po {@link Warning#isActive()} odbywa się w pamięci.
     */
    @Override
    public List<Warning> findActiveWarnings() throws PersistenceException {
        List<Warning> today = jsonWriter.readWarnings(
                java.time.LocalDate.now().toString());
        return today.stream().filter(Warning::isActive).toList();
    }

    /**
     * Aktywne ostrzeżenia spełniające minimalny poziom (np. >= ŻÓŁTE).
     *
     * @param minLevel minimalny poziom alarmu — niższe poziomy są odfiltrowywane
     * @return lista ostrzeżeń spełniających warunek
     */
    @Override
    public List<Warning> findActiveWarningsByMinLevel(WarningLevel minLevel)
            throws PersistenceException {
        return findActiveWarnings().stream()
                .filter(w -> w.meetsLevel(minLevel))
                .toList();
    }

    /**
     * W trybie FILE nie ma sensu czyszczenia wygasłych — plik ostrzeżeń jest
     * nadpisywany przy każdym cyklu schedulera, więc „wygasłe" znikają same
     * przez następne odświeżenie.
     *
     * @return 0 — operacja jest no-op w tym trybie
     */
    @Override
    public int deleteExpiredWarnings() throws PersistenceException {
        log.debug("deleteExpiredWarnings — brak akcji w trybie FILE (nadpisywanie przy odświeżeniu)");
        return 0;
    }

    // =========================================================================
    // CZYSZCZENIE WSZYSTKICH DANYCH
    // =========================================================================

    /**
     * Usuwa wszystkie pliki danych pomiarowych (po jednym na stację) oraz
     * wszystkie pliki ostrzeżeń. Stacje (stations.json) i ustawienia aplikacji
     * pozostają nietknięte. Wywołujący odpowiada za potwierdzenie od użytkownika.
     *
     * Iteruje po wszystkich stacjach (aktywne + nieaktywne), żeby usunąć też
     * pliki stacji wyłączonych. Pliki, których nie da się usunąć, są logowane
     * jako warning, ale operacja kontynuuje.
     */
    @Override
    public void clearAllData() throws PersistenceException {
        int meteoDeleted    = 0;
        int hydroDeleted    = 0;
        int warningsDeleted = 0;

        // Pliki pomiarowe — po jednym pliku na stację. Iterujemy po WSZYSTKICH
        // stacjach (aktywne + nieaktywne), żeby nic nie zostało osierocone.
        List<Station> all = findAllStations();
        for (Station s : all) {
            Path file = (s.getType() == StationType.METEO)
                    ? pathResolver.resolveMeteoFile(s.getId(), s.getName(), "json")
                    : pathResolver.resolveHydroFile(s.getId(), s.getName(), "json");
            try {
                if (Files.deleteIfExists(file)) {
                    if (s.getType() == StationType.METEO) meteoDeleted++;
                    else                                  hydroDeleted++;
                }
            } catch (IOException e) {
                log.warn("Nie udało się usunąć pliku danych {}: {}", file, e.getMessage());
            }
        }

        // Pliki ostrzeżeń — wszystkie .json w katalogu warnings. Nazwa pliku
        // zawiera datę, więc nie da się ich znaleźć inaczej niż przez listing
        // katalogu. „dummy" jako parametr daty jest tu tylko po to, żeby
        // wyciągnąć katalog rodzica — nazwa pliku nie ma znaczenia.
        Path probe = pathResolver.resolveWarningsFile("dummy", "json");
        Path warningsDir = probe.getParent();
        if (warningsDir != null && Files.isDirectory(warningsDir)) {
            try (var stream = Files.list(warningsDir)) {
                List<Path> jsonFiles = stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .toList();
                for (Path p : jsonFiles) {
                    try {
                        if (Files.deleteIfExists(p)) warningsDeleted++;
                    } catch (IOException e) {
                        log.warn("Nie udało się usunąć pliku ostrzeżeń {}: {}",
                                p, e.getMessage());
                    }
                }
            } catch (IOException e) {
                throw new PersistenceException(
                        "Błąd listowania katalogu ostrzeżeń: " + warningsDir, e);
            }
        }

        log.info("Wyczyszczono wszystkie dane: meteo={}, hydro={}, warnings={}",
                meteoDeleted, hydroDeleted, warningsDeleted);
    }

    // =========================================================================
    // ZARZĄDZANIE ZASOBAMI
    // =========================================================================

    /**
     * W trybie FILE nie ma trwałych zasobów do zwolnienia (brak puli połączeń,
     * brak otwartych handle'ów). Metoda jest tu dla spójności z interfejsem
     * i zostawia ślad w logu, że repozytorium zostało zamknięte.
     */
    @Override
    public void close() {
        log.info("FileRepository zamknięty");
    }

    // =========================================================================
    // METODY POMOCNICZE
    // =========================================================================

    /**
     * Serializuje listę stacji do JSON-a i nadpisuje plik stations.json.
     * Tworzy katalog nadrzędny jeśli nie istnieje
     * (zob. {@link #ensureParentDirExists(Path)}).
     *
     * @param stations lista stacji do zapisania (może być pusta)
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
     */
    private void writeStationsFile(List<Station> stations) throws PersistenceException {
        Path file = pathResolver.getStationsConfigFile();
        ensureParentDirExists(file);
        try {
            String json = JsonParser.toPrettyJson(stations);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new PersistenceException("Błąd zapisu pliku stacji", e);
        }
    }

    /**
     * Nadpisuje dowolny plik JSON nową zawartością. Używana przez metody
     * cleanup ({@link #deleteMeteoOlderThan}, {@link #deleteHydroOlderThan})
     * — po odfiltrowaniu starych rekordów lista jest zrzucana z powrotem.
     *
     * @param file plik do nadpisania (tworzony jeśli nie istnieje)
     * @param data dane do zserializowania jako tablica JSON
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
     */
    private void rewriteJsonFile(Path file, List<?> data) throws PersistenceException {
        ensureParentDirExists(file);
        try {
            String json = JsonParser.toPrettyJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new PersistenceException("Błąd nadpisywania pliku: " + file, e);
        }
    }

    /**
     * Tworzy katalog nadrzędny dla pliku, jeśli nie istnieje.
     *
     * Operacja idempotentna — gdy katalog już istnieje, nic się nie dzieje.
     * Wywoływana przed każdym zapisem, żeby zabezpieczyć się przed scenariuszem,
     * w którym użytkownik (lub coś innego) usuwa katalog data/ podczas
     * działania aplikacji — bez tego pierwsze odświeżenie po usunięciu kończy
     * się NoSuchFileException, mimo że PathResolver tworzy katalogi przy starcie.
     */
    private void ensureParentDirExists(Path file) throws PersistenceException {
        Path parent = file.getParent();
        if (parent == null) return;
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new PersistenceException(
                    "Nie udało się utworzyć katalogu " + parent + ": " + e.getMessage(), e);
        }
    }
}