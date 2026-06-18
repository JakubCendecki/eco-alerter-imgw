package ecoalerter.scheduler;

import ecoalerter.util.AppLogger;
import ecoalerter.util.JsonParser;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Przechowuje indywidualne interwały odpytywania API dla każdej stacji.
 *
 * Domyślnie wszystkie stacje korzystają z globalnego interwału z AppConfig.
 * Użytkownik może nadpisać interwał dla dowolnej stacji z poziomu GUI
 * (SchedulerPanel). Zmiany są zapisywane do pliku schedule.json.
 *
 * Klucz mapy to stationId. Wartość 0 oznacza "użyj globalnego interwału".
 * Minimalna dozwolona wartość to 60 sekund (limit API IMGW).
 */
public class ScheduleConfig {

    private static final Logger log = AppLogger.get(ScheduleConfig.class);

    static final int MIN_INTERVAL_SECONDS = 60;

    private final Map<String, Integer> stationIntervals;

    // -------------------------------------------------------------------------
    // Konstruktory
    // -------------------------------------------------------------------------

    public ScheduleConfig() {
        this.stationIntervals = new HashMap<>();
    }

    private ScheduleConfig(Map<String, Integer> intervals) {
        this.stationIntervals = new HashMap<>(intervals);
    }

    // -------------------------------------------------------------------------
    // Odczyt i zapis interwałów
    // -------------------------------------------------------------------------

    /**
     * Zwraca interwał dla stacji w sekundach.
     * Jeśli stacja nie ma ustawionego własnego interwału, zwraca defaultInterval.
     * Wynik jest zawsze co najmniej MIN_INTERVAL_SECONDS.
     *
     * @param stationId       identyfikator stacji
     * @param defaultInterval globalny interwał z AppConfig
     * @return efektywny interwał w sekundach, minimum 60
     */
    public int getInterval(String stationId, int defaultInterval) {
        int custom = stationIntervals.getOrDefault(stationId, 0);
        int effective = custom > 0 ? custom : defaultInterval;
        return Math.max(effective, MIN_INTERVAL_SECONDS);
    }

    /**
     * Ustawia własny interwał dla stacji.
     * Wartość jest automatycznie ograniczana do minimum MIN_INTERVAL_SECONDS.
     *
     * @param stationId identyfikator stacji
     * @param seconds   żądany interwał w sekundach
     */
    public void setInterval(String stationId, int seconds) {
        int clamped = Math.max(seconds, MIN_INTERVAL_SECONDS);
        if (clamped != seconds) {
            log.warn("Interwał {} s dla stacji {} jest poniżej minimum — ustawiono {} s",
                    seconds, stationId, MIN_INTERVAL_SECONDS);
        }
        stationIntervals.put(stationId, clamped);
        log.debug("Interwał stacji {}: {} s", stationId, clamped);
    }

    /**
     * Usuwa własny interwał stacji — wróci do globalnego.
     *
     * @param stationId identyfikator stacji
     */
    public void resetInterval(String stationId) {
        stationIntervals.remove(stationId);
        log.debug("Zresetowano interwał stacji {} do globalnego", stationId);
    }

    /**
     * Sprawdza czy stacja ma ustawiony własny interwał.
     *
     * @param stationId identyfikator stacji
     * @return true jeśli stacja ma własny interwał
     */
    public boolean hasCustomInterval(String stationId) {
        return stationIntervals.containsKey(stationId);
    }

    /**
     * Zwraca kopię mapy wszystkich niestandardowych interwałów.
     *
     * @return mapa stationId -> intervalSeconds
     */
    public Map<String, Integer> getAllCustomIntervals() {
        return new HashMap<>(stationIntervals);
    }

    // -------------------------------------------------------------------------
    // Zapis i odczyt z pliku
    // -------------------------------------------------------------------------

    /**
     * Zapisuje konfigurację interwałów do pliku JSON.
     *
     * @param configFile ścieżka do pliku docelowego
     * @throws IOException gdy zapis się nie powiedzie
     */
    public void save(Path configFile) throws IOException {
        String json = JsonParser.toPrettyJson(stationIntervals);
        Files.writeString(configFile, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Zapisano konfigurację harmonogramu do: {}", configFile);
    }

    /**
     * Wczytuje konfigurację interwałów z pliku JSON.
     * Jeśli plik nie istnieje, zwraca pustą konfigurację (bez rzucania wyjątku).
     *
     * @param configFile ścieżka do pliku konfiguracyjnego
     * @return załadowana konfiguracja lub pusta przy braku pliku
     */
    public static ScheduleConfig load(Path configFile) {
        if (!Files.exists(configFile)) {
            log.debug("Brak pliku schedule.json — używam pustej konfiguracji");
            return new ScheduleConfig();
        }

        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            if (json.isBlank()) return new ScheduleConfig();

            // Gson nie może bezpośrednio deserializować Map<String,Integer>
            // przez type erasure — parsujemy ręcznie przez JsonElement
            com.google.gson.JsonObject obj = com.google.gson.JsonParser
                    .parseString(json).getAsJsonObject();

            Map<String, Integer> intervals = new HashMap<>();
            for (var entry : obj.entrySet()) {
                intervals.put(entry.getKey(), entry.getValue().getAsInt());
            }

            log.info("Wczytano konfigurację harmonogramu ({} wpisów) z: {}",
                    intervals.size(), configFile);
            return new ScheduleConfig(intervals);

        } catch (IOException | com.google.gson.JsonParseException e) {
            log.warn("Błąd odczytu schedule.json — używam pustej konfiguracji: {}",
                    e.getMessage());
            return new ScheduleConfig();
        }
    }
}