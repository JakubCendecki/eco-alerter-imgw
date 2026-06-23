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

    /**
     * @param pathResolver komponent do budowania ścieżek plików per stacja
     *                     (data/meteo/&lt;id&gt;.json itd.)
     */
    public JsonFileWriter(PathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    // -------------------------------------------------------------------------
    // Zapis danych meteo
    // -------------------------------------------------------------------------

    /**
     * Dopisuje pojedynczy pomiar meteo do pliku JSON stacji. Tworzy plik
     * (i katalogi) jeśli nie istnieje. Duplikaty (ten sam timestamp) są
     * pomijane — zob. {@link #appendToJsonArray}.
     *
     * @param data pomiar do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
     */
    public void writeMeteo(MeteoData data) throws PersistenceException {
        writeMeteoList(List.of(data));
    }

    /**
     * Dopisuje listę pomiarów meteo. Grupuje po stacji — każdy pomiar trafia
     * do pliku odpowiadającego jego {@code stationId + stationName}.
     *
     * @param dataList lista pomiarów (null lub pusta — brak operacji)
     * @throws PersistenceException gdy zapis któregokolwiek pliku się nie powiedzie
     */
    public void writeMeteoList(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

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

    // -------------------------------------------------------------------------
    // Zapis danych hydro
    // -------------------------------------------------------------------------

    /**
     * Dopisuje pojedynczy pomiar hydro do pliku JSON stacji.
     *
     * @param data pomiar do zapisania
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
     */
    public void writeHydro(HydroData data) throws PersistenceException {
        writeHydroList(List.of(data));
    }

    /**
     * Dopisuje listę pomiarów hydro do odpowiednich plików JSON
     * (po jednym pliku na stację).
     *
     * @param dataList lista pomiarów (null lub pusta — brak operacji)
     * @throws PersistenceException gdy zapis któregokolwiek pliku się nie powiedzie
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

    // -------------------------------------------------------------------------
    // Zapis ostrzeżeń
    // -------------------------------------------------------------------------

    /**
     * Zapisuje listę ostrzeżeń do pliku JSON z bieżącą datą w nazwie. Plik
     * jest zawsze NADPISYWANY (nie dopisywany) — zawiera bieżący stan ostrzeżeń.
     * Tworzy katalog warnings jeśli nie istnieje.
     *
     * @param warnings lista ostrzeżeń (null → zapisany jako pusta tablica JSON)
     * @throws PersistenceException gdy zapis pliku się nie powiedzie
     */
    public void writeWarnings(List<Warning> warnings) throws PersistenceException {
        if (warnings == null) warnings = List.of();

        Path file = pathResolver.resolveWarningsFile(DateTimeUtil.todayForFilename(), "json");
        ensureParentDirExists(file);

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
     * @param date data w formacie yyyy-MM-dd (część nazwy pliku)
     * @return lista ostrzeżeń lub pusta lista gdy plik nie istnieje
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
     */
    public List<Warning> readWarnings(String date) throws PersistenceException {
        Path file = pathResolver.resolveWarningsFile(date, "json");
        return readJsonArray(file, Warning.class);
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    /**
     * Dopisuje jeden obiekt do tablicy JSON w pliku, pomijając zapis gdy
     * rekord z tym samym znacznikiem czasu już istnieje w pliku.
     *
     * Bez tej kontroli każdy restart aplikacji (scheduler odpytuje API
     * natychmiast po starcie) dopisywałby kolejną kopię tego samego pomiaru,
     * jeśli IMGW jeszcze nie zaktualizowało danych — co prowadziłoby do
     * wielu identycznych wierszy o tej samej godzinie pomiaru.
     *
     * @param file  ścieżka do pliku JSON
     * @param item  obiekt do dopisania
     * @param clazz klasa obiektu (potrzebna dla deserializacji istniejącej zawartości)
     * @param <T>   typ obiektu (MeteoData lub HydroData)
     * @throws PersistenceException gdy odczyt lub zapis pliku się nie powiedzie
     */
    private <T> void appendToJsonArray(Path file, T item, Class<T> clazz)
            throws PersistenceException {
        List<T> existing = readJsonArray(file, clazz);

        if (containsSameTimestamp(existing, item)) {
            log.debug("Pominięto duplikat (ten sam znacznik czasu pomiaru) w {}", file.getFileName());
            return;
        }

        existing.add(item);

        ensureParentDirExists(file);

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
     * Sprawdza czy lista zawiera już rekord z tym samym znacznikiem czasu
     * co nowy element. Obsługuje MeteoData i HydroData — jedyne dwa typy
     * przechodzące przez {@link #appendToJsonArray} (Warning zawsze nadpisuje
     * cały plik, nie dopisuje, więc nie potrzebuje tej kontroli).
     *
     * @param existing lista istniejących rekordów
     * @param newItem  nowy rekord do sprawdzenia
     * @param <T>      typ rekordu
     * @return true jeśli któryś z istniejących ma ten sam znacznik czasu
     */
    private <T> boolean containsSameTimestamp(List<T> existing, T newItem) {
        java.time.LocalDateTime newTimestamp = extractTimestamp(newItem);
        if (newTimestamp == null) return false;

        for (T item : existing) {
            if (newTimestamp.equals(extractTimestamp(item))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wyciąga znacznik czasu pomiaru z MeteoData/HydroData.
     * Dla innych typów (lub null) zwraca null.
     */
    private java.time.LocalDateTime extractTimestamp(Object item) {
        if (item instanceof MeteoData m) return m.getTimestamp();
        if (item instanceof HydroData h) return h.getTimestamp();
        return null;
    }

    /**
     * Odczytuje tablicę JSON z pliku i mapuje na listę obiektów.
     * Zwraca pustą, modyfikowalną listę gdy plik nie istnieje lub jest pusty
     * — wygodne dla wywołań typu „przeczytaj, dodaj rekord, zapisz".
     *
     * @param file  ścieżka pliku JSON
     * @param clazz klasa elementu tablicy
     * @param <T>   typ elementu
     * @return modyfikowalna lista (nigdy null)
     * @throws PersistenceException gdy odczyt pliku się nie powiedzie
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