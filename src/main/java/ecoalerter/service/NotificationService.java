package ecoalerter.service;

import ecoalerter.model.Station;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.scheduler.FetchTask;
import ecoalerter.scheduler.TaskSchedulerManager;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Serwis powiadomień łączący warstwę schedulera z warstwą GUI.
 *
 * Implementuje FetchTask.FetchListener oraz TaskSchedulerManager.WarningListener
 * i jest rejestrowany bezpośrednio w schedulerze przy starcie aplikacji.
 *
 * Odpowiada za:
 * - śledzenie stanu zdrowia każdej stacji (ostatni sukces, błędy),
 * - przekazywanie zdarzeń do GUI przez AppEventListener,
 * - bezpieczne wywołanie listenerów GUI na EDT (Swing Event Dispatch Thread).
 *
 * Wszystkie operacje na mapie stationStatuses są thread-safe (ConcurrentHashMap).
 * Lista listenerów używa CopyOnWriteArrayList — bezpieczna przy iteracji
 * w środku wywołania zwrotnego.
 */
public class NotificationService
        implements FetchTask.FetchListener, TaskSchedulerManager.WarningListener {

    private static final Logger log = AppLogger.get(NotificationService.class);

    /** Maksymalna liczba zdarzeń w historii trzymanej w pamięci. */
    private static final int MAX_EVENT_HISTORY = 100;

    private final Map<String, StationStatus>    stationStatuses = new ConcurrentHashMap<>();
    private final List<AppEventListener>        listeners       = new CopyOnWriteArrayList<>();
    private final java.util.Deque<AppEvent>     eventHistory    =
            new java.util.concurrent.LinkedBlockingDeque<>(MAX_EVENT_HISTORY);

    // =========================================================================
    // Interfejsy i typy publiczne
    // =========================================================================

    /**
     * Typ zdarzenia aplikacji przekazywanego do GUI.
     */
    public enum EventType {
        /** Nowe dane zostały pobrane i zapisane dla stacji. */
        DATA_UPDATED,
        /** Wykryto nowe aktywne ostrzeżenie IMGW. */
        WARNING_DETECTED,
        /** Stacja zgłosiła błąd pobierania. */
        STATION_ERROR,
        /** Lista ostrzeżeń została odświeżona. */
        WARNINGS_REFRESHED,
        /** Stacja została dodana, usunięta lub edytowana — inne panele powinny się odświeżyć. */
        STATIONS_CHANGED
    }

    /**
     * Zdarzenie aplikacji przekazywane do listenerów GUI.
     */
    public static class AppEvent {
        private final EventType     type;
        private final Object        payload;
        private final LocalDateTime occurredAt;

        public AppEvent(EventType type, Object payload) {
            this.type       = type;
            this.payload    = payload;
            this.occurredAt = LocalDateTime.now();
        }

        public EventType     getType()       { return type; }
        public Object        getPayload()    { return payload; }
        public LocalDateTime getOccurredAt() { return occurredAt; }

        @Override
        public String toString() {
            return String.format("AppEvent{type=%s, at=%s}", type, occurredAt);
        }
    }

    /**
     * Odbiornik zdarzeń aplikacji. Implementowany przez panele GUI.
     * Wywołanie następuje na wątku EDT — bezpieczne dla operacji Swing.
     */
    public interface AppEventListener {
        void onEvent(AppEvent event);
    }

    /**
     * Stan zdrowia stacji — agreguje informacje o ostatnich cyklach pobierania.
     */
    public static class StationStatus {

        private final String        stationId;
        private volatile LocalDateTime lastSuccessAt;
        private volatile LocalDateTime lastErrorAt;
        private volatile String        lastErrorMessage;
        private volatile int           consecutiveErrors;

        StationStatus(String stationId) {
            this.stationId         = stationId;
            this.consecutiveErrors = 0;
        }

        void recordSuccess() {
            this.lastSuccessAt     = LocalDateTime.now();
            this.consecutiveErrors = 0;
        }

        void recordError(String message) {
            this.lastErrorAt      = LocalDateTime.now();
            this.lastErrorMessage = message;
            this.consecutiveErrors++;
        }

        /**
         * Stacja jest zdrowa jeśli ostatni cykl zakończył się sukcesem
         * lub nigdy nie zgłosiła błędu.
         *
         * @return true jeśli stacja działa poprawnie
         */
        public boolean isHealthy() {
            return consecutiveErrors == 0;
        }

        /**
         * Stacja jest w stanie krytycznym gdy zgłosiła co najmniej 3 kolejne błędy.
         *
         * @return true jeśli stacja wymaga uwagi
         */
        public boolean isCritical() {
            return consecutiveErrors >= 3;
        }

        public String        getStationId()          { return stationId; }
        public LocalDateTime getLastSuccessAt()       { return lastSuccessAt; }
        public LocalDateTime getLastErrorAt()         { return lastErrorAt; }
        public String        getLastErrorMessage()    { return lastErrorMessage; }
        public int           getConsecutiveErrors()   { return consecutiveErrors; }
    }

    // =========================================================================
    // Rejestracja listenerów
    // =========================================================================

    /**
     * Rejestruje odbiorcę zdarzeń aplikacji (np. panel GUI).
     * Metoda jest thread-safe.
     *
     * @param listener odbiorca do zarejestrowania; null jest ignorowany
     */
    public void addListener(AppEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    /**
     * Wyrejestrowuje odbiorcę zdarzeń.
     *
     * @param listener odbiorca do usunięcia
     */
    public void removeListener(AppEventListener listener) {
        listeners.remove(listener);
    }

    // =========================================================================
    // Implementacja FetchTask.FetchListener
    // =========================================================================

    @Override
    public void onSuccess(Station station) {
        StationStatus status = getOrCreateStatus(station.getId());
        status.recordSuccess();

        log.debug("Stacja {} — cykl OK", station.getId());
        publish(new AppEvent(EventType.DATA_UPDATED, station));
    }

    @Override
    public void onError(Station station, String errorMessage) {
        StationStatus status = getOrCreateStatus(station.getId());
        status.recordError(errorMessage);

        log.warn("Stacja {} — błąd #{}: {}",
                station.getId(), status.getConsecutiveErrors(), errorMessage);

        publish(new AppEvent(EventType.STATION_ERROR, status));

        if (status.isCritical()) {
            log.error("Stacja {} jest w stanie krytycznym ({} kolejnych błędów)",
                    station.getId(), status.getConsecutiveErrors());
        }
    }

    // =========================================================================
    // Powiadomienie o zmianie listy stacji (wywoływane z GUI po CRUD)
    // =========================================================================

    /**
     * Publikuje zdarzenie informujące, że lista stacji się zmieniła
     * (dodano, usunięto lub edytowano stację, albo zmieniono jej aktywność).
     *
     * Wywoływane przez panel zarządzający stacjami po każdej udanej operacji
     * mutującej, żeby inne panele (podgląd danych, harmonogram) mogły
     * odświeżyć swoje listy bez ręcznej akcji użytkownika.
     */
    public void notifyStationsChanged() {
        log.debug("Lista stacji zmieniona — powiadamianie odbiorców");
        publish(new AppEvent(EventType.STATIONS_CHANGED, null));
    }

    // =========================================================================
    // Implementacja TaskSchedulerManager.WarningListener
    // =========================================================================

    @Override
    public void onWarningsRefreshed(List<Warning> warnings) {
        log.debug("Odświeżono {} ostrzeżeń", warnings.size());
        publish(new AppEvent(EventType.WARNINGS_REFRESHED, warnings));

        // Osobne zdarzenie dla każdego ostrzeżenia RED/ORANGE — alerty w GUI
        warnings.stream()
                .filter(w -> w.getLevel() != null
                          && w.getLevel().isAtLeast(WarningLevel.ORANGE))
                .forEach(w -> publish(new AppEvent(EventType.WARNING_DETECTED, w)));
    }

    // =========================================================================
    // Dostęp do statusów stacji
    // =========================================================================

    /**
     * Zwraca status stacji lub null gdy stacja nie ma jeszcze żadnego cyklu.
     *
     * @param stationId identyfikator stacji
     * @return status stacji lub null
     */
    public StationStatus getStatus(String stationId) {
        return stationStatuses.get(stationId);
    }

    /**
     * Zwraca niemodyfikowalną kopię mapy wszystkich statusów stacji.
     *
     * @return mapa stationId → StationStatus
     */
    public Map<String, StationStatus> getAllStatuses() {
        return Collections.unmodifiableMap(stationStatuses);
    }

    /**
     * Zwraca liczbę stacji będących aktualnie w stanie krytycznym
     * (3 lub więcej kolejnych błędów).
     *
     * @return liczba stacji z błędem krytycznym
     */
    public long getCriticalStationCount() {
        return stationStatuses.values().stream()
                .filter(StationStatus::isCritical)
                .count();
    }

    /**
     * Usuwa status stacji — wywołaj po usunięciu stacji z systemu.
     *
     * @param stationId identyfikator stacji
     */
    public void clearStatus(String stationId) {
        stationStatuses.remove(stationId);
    }

    // =========================================================================
    // Historia zdarzeń
    // =========================================================================

    /**
     * Zwraca niemodyfikowalną kopię historii ostatnich zdarzeń.
     * Historia jest ograniczona do MAX_EVENT_HISTORY wpisów.
     *
     * @return lista ostatnich zdarzeń w kolejności chronologicznej
     */
    public List<AppEvent> getEventHistory() {
        return List.copyOf(eventHistory);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Publikuje zdarzenie do wszystkich zarejestrowanych listenerów.
     * Zdarzenie jest też dodawane do historii w pamięci.
     * Wywołania listenerów są opakowywane w SwingUtilities.invokeLater,
     * co gwarantuje wykonanie na EDT.
     */
    private void publish(AppEvent event) {
        // Dodaj do historii (usuń najstarszy jeśli pełna)
        if (!eventHistory.offerLast(event)) {
            eventHistory.pollFirst();
            eventHistory.offerLast(event);
        }

        if (listeners.isEmpty()) return;

        // Dispatch na EDT — bezpieczny dla komponentów Swing
        javax.swing.SwingUtilities.invokeLater(() -> {
            for (AppEventListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    log.warn("Błąd w AppEventListener podczas obsługi {}: {}",
                            event.getType(), e.getMessage());
                }
            }
        });
    }

    private StationStatus getOrCreateStatus(String stationId) {
        return stationStatuses.computeIfAbsent(stationId, StationStatus::new);
    }
}