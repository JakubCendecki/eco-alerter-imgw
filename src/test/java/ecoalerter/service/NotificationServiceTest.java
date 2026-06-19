package ecoalerter.service;

import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy jednostkowe NotificationService.
 *
 * Listenery GUI są wywoływane asynchronicznie przez SwingUtilities.invokeLater,
 * więc testy sprawdzające dostarczenie zdarzeń do AppEventListener używają
 * flushEdt() — wywołania invokeAndWait, które blokuje aż wszystkie wcześniej
 * zakolejkowane zadania EDT zostaną wykonane.
 */
class NotificationServiceTest {

    private NotificationService service;

    @BeforeAll
    static void setHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        service = new NotificationService();
    }

    // -------------------------------------------------------------------------
    // onSuccess — śledzenie statusu stacji
    // -------------------------------------------------------------------------

    @Test
    void onSuccess_createsStatusForNewStation() {
        Station station = meteoStation("12200");

        service.onSuccess(station);

        NotificationService.StationStatus status = service.getStatus("12200");
        assertNotNull(status);
        assertTrue(status.isHealthy());
        assertNotNull(status.getLastSuccessAt());
    }

    @Test
    void onSuccess_resetsConsecutiveErrors() {
        Station station = meteoStation("12200");

        service.onError(station, "błąd 1");
        service.onError(station, "błąd 2");
        service.onSuccess(station);

        assertEquals(0, service.getStatus("12200").getConsecutiveErrors());
        assertTrue(service.getStatus("12200").isHealthy());
    }

    @Test
    void onSuccess_addsEventToHistory() {
        service.onSuccess(meteoStation("12200"));

        List<NotificationService.AppEvent> history = service.getEventHistory();

        assertEquals(1, history.size());
        assertEquals(NotificationService.EventType.DATA_UPDATED, history.get(0).getType());
    }

    // -------------------------------------------------------------------------
    // onError — śledzenie błędów
    // -------------------------------------------------------------------------

    @Test
    void onError_createsStatusWithError() {
        Station station = meteoStation("12200");

        service.onError(station, "timeout");

        NotificationService.StationStatus status = service.getStatus("12200");
        assertNotNull(status);
        assertFalse(status.isHealthy());
        assertEquals(1, status.getConsecutiveErrors());
        assertEquals("timeout", status.getLastErrorMessage());
        assertNotNull(status.getLastErrorAt());
    }

    @Test
    void onError_incrementsConsecutiveErrorsOnRepeat() {
        Station station = meteoStation("12200");

        service.onError(station, "err1");
        service.onError(station, "err2");
        service.onError(station, "err3");

        assertEquals(3, service.getStatus("12200").getConsecutiveErrors());
    }

    @Test
    void onError_threeConsecutiveErrors_marksCritical() {
        Station station = meteoStation("12200");

        service.onError(station, "err1");
        service.onError(station, "err2");
        service.onError(station, "err3");

        assertTrue(service.getStatus("12200").isCritical());
    }

    @Test
    void onError_twoConsecutiveErrors_notYetCritical() {
        Station station = meteoStation("12200");

        service.onError(station, "err1");
        service.onError(station, "err2");

        assertFalse(service.getStatus("12200").isCritical());
    }

    @Test
    void onError_addsStationErrorEventToHistory() {
        service.onError(meteoStation("12200"), "timeout");

        List<NotificationService.AppEvent> history = service.getEventHistory();

        assertEquals(1, history.size());
        assertEquals(NotificationService.EventType.STATION_ERROR, history.get(0).getType());
    }

    // -------------------------------------------------------------------------
    // getAllStatuses / getCriticalStationCount / clearStatus
    // -------------------------------------------------------------------------

    @Test
    void getAllStatuses_returnsAllTrackedStations() {
        service.onSuccess(meteoStation("12200"));
        service.onError(hydroStation("150180180"), "err");

        var statuses = service.getAllStatuses();

        assertEquals(2, statuses.size());
        assertTrue(statuses.containsKey("12200"));
        assertTrue(statuses.containsKey("150180180"));
    }

    @Test
    void getAllStatuses_returnsUnmodifiableMap() {
        service.onSuccess(meteoStation("12200"));

        var statuses = service.getAllStatuses();

        assertThrows(UnsupportedOperationException.class,
                () -> statuses.put("99999", null));
    }

    @Test
    void getCriticalStationCount_countsOnlyCriticalStations() {
        Station critical    = meteoStation("12200");
        Station notCritical = meteoStation("12385");

        service.onError(critical, "e1");
        service.onError(critical, "e2");
        service.onError(critical, "e3");
        service.onError(notCritical, "e1");

        assertEquals(1, service.getCriticalStationCount());
    }

    @Test
    void getCriticalStationCount_noStations_returnsZero() {
        assertEquals(0, service.getCriticalStationCount());
    }

    @Test
    void clearStatus_removesStationFromTracking() {
        service.onSuccess(meteoStation("12200"));
        assertNotNull(service.getStatus("12200"));

        service.clearStatus("12200");

        assertNull(service.getStatus("12200"));
    }

    @Test
    void getStatus_nonExistentStation_returnsNull() {
        assertNull(service.getStatus("99999"));
    }

    // -------------------------------------------------------------------------
    // onWarningsRefreshed
    // -------------------------------------------------------------------------

    @Test
    void onWarningsRefreshed_addsWarningsRefreshedEvent() {
        List<Warning> warnings = List.of(warning("W001", WarningLevel.YELLOW));

        service.onWarningsRefreshed(warnings);

        List<NotificationService.AppEvent> history = service.getEventHistory();
        assertTrue(history.stream()
                .anyMatch(e -> e.getType() == NotificationService.EventType.WARNINGS_REFRESHED));
    }

    @Test
    void onWarningsRefreshed_orangeAndRedWarnings_emitWarningDetectedEvents() {
        List<Warning> warnings = List.of(
                warning("W001", WarningLevel.YELLOW), // poniżej progu
                warning("W002", WarningLevel.ORANGE),
                warning("W003", WarningLevel.RED)
        );

        service.onWarningsRefreshed(warnings);

        long detectedCount = service.getEventHistory().stream()
                .filter(e -> e.getType() == NotificationService.EventType.WARNING_DETECTED)
                .count();

        assertEquals(2, detectedCount, "Tylko ORANGE i RED powinny generować WARNING_DETECTED");
    }

    @Test
    void onWarningsRefreshed_onlyYellowWarnings_noWarningDetectedEvents() {
        List<Warning> warnings = List.of(warning("W001", WarningLevel.YELLOW));

        service.onWarningsRefreshed(warnings);

        boolean hasDetected = service.getEventHistory().stream()
                .anyMatch(e -> e.getType() == NotificationService.EventType.WARNING_DETECTED);

        assertFalse(hasDetected);
    }

    @Test
    void onWarningsRefreshed_emptyList_onlyEmitsRefreshedEvent() {
        service.onWarningsRefreshed(List.of());

        List<NotificationService.AppEvent> history = service.getEventHistory();
        assertEquals(1, history.size());
        assertEquals(NotificationService.EventType.WARNINGS_REFRESHED, history.get(0).getType());
    }

    @Test
    void onWarningsRefreshed_warningWithNullLevel_doesNotEmitDetectedEvent() {
        Warning w = new Warning();
        w.setId("W999");
        w.setLevel(null);

        service.onWarningsRefreshed(List.of(w));

        boolean hasDetected = service.getEventHistory().stream()
                .anyMatch(e -> e.getType() == NotificationService.EventType.WARNING_DETECTED);
        assertFalse(hasDetected);
    }

    // -------------------------------------------------------------------------
    // Listenery — dostarczenie zdarzeń na EDT
    // -------------------------------------------------------------------------

    @Test
    void addListener_receivesEventOnSuccess() throws Exception {
        List<NotificationService.AppEvent> received = new CopyOnWriteArrayList<>();
        service.addListener(received::add);

        service.onSuccess(meteoStation("12200"));
        flushEdt();

        assertEquals(1, received.size());
        assertEquals(NotificationService.EventType.DATA_UPDATED, received.get(0).getType());
    }

    @Test
    void addListener_receivesEventOnError() throws Exception {
        List<NotificationService.AppEvent> received = new CopyOnWriteArrayList<>();
        service.addListener(received::add);

        service.onError(meteoStation("12200"), "timeout");
        flushEdt();

        assertEquals(1, received.size());
        assertEquals(NotificationService.EventType.STATION_ERROR, received.get(0).getType());
    }

    @Test
    void addListener_null_isIgnored() {
        assertDoesNotThrow(() -> service.addListener(null));
    }

    @Test
    void removeListener_stopsReceivingEvents() throws Exception {
        List<NotificationService.AppEvent> received = new CopyOnWriteArrayList<>();
        NotificationService.AppEventListener listener = received::add;

        service.addListener(listener);
        service.onSuccess(meteoStation("12200"));
        flushEdt();
        assertEquals(1, received.size());

        service.removeListener(listener);
        service.onSuccess(meteoStation("12385"));
        flushEdt();

        assertEquals(1, received.size(), "Po usunięciu listener nie powinien dostać kolejnego zdarzenia");
    }

    @Test
    void multipleListeners_allReceiveEvent() throws Exception {
        List<NotificationService.AppEvent> received1 = new CopyOnWriteArrayList<>();
        List<NotificationService.AppEvent> received2 = new CopyOnWriteArrayList<>();

        service.addListener(received1::add);
        service.addListener(received2::add);

        service.onSuccess(meteoStation("12200"));
        flushEdt();

        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
    }

    @Test
    void listenerThrowsException_doesNotPreventOtherListeners() throws Exception {
        List<NotificationService.AppEvent> received = new CopyOnWriteArrayList<>();

        service.addListener(event -> { throw new RuntimeException("crash"); });
        service.addListener(received::add);

        service.onSuccess(meteoStation("12200"));
        flushEdt();

        assertEquals(1, received.size(), "Drugi listener powinien dostać zdarzenie mimo crasha pierwszego");
    }

    @Test
    void noListenersRegistered_doesNotThrow() {
        assertDoesNotThrow(() -> service.onSuccess(meteoStation("12200")));
    }

    // -------------------------------------------------------------------------
    // getEventHistory — limit i kolejność
    // -------------------------------------------------------------------------

    @Test
    void getEventHistory_returnsEventsInChronologicalOrder() {
        service.onSuccess(meteoStation("12200"));
        service.onError(meteoStation("12385"), "err");

        List<NotificationService.AppEvent> history = service.getEventHistory();

        assertEquals(2, history.size());
        assertEquals(NotificationService.EventType.DATA_UPDATED,  history.get(0).getType());
        assertEquals(NotificationService.EventType.STATION_ERROR, history.get(1).getType());
    }

    @Test
    void getEventHistory_isImmutableCopy() {
        service.onSuccess(meteoStation("12200"));

        List<NotificationService.AppEvent> history = service.getEventHistory();

        assertThrows(UnsupportedOperationException.class,
                () -> history.add(new NotificationService.AppEvent(
                        NotificationService.EventType.DATA_UPDATED, null)));
    }

    @Test
    void getEventHistory_emptyInitially() {
        assertTrue(service.getEventHistory().isEmpty());
    }

    // -------------------------------------------------------------------------
    // AppEvent — pola podstawowe
    // -------------------------------------------------------------------------

    @Test
    void appEvent_storesTypeAndPayload() {
        Station station = meteoStation("12200");
        var event = new NotificationService.AppEvent(
                NotificationService.EventType.DATA_UPDATED, station);

        assertEquals(NotificationService.EventType.DATA_UPDATED, event.getType());
        assertSame(station, event.getPayload());
        assertNotNull(event.getOccurredAt());
    }

    // =========================================================================
    // Fabryki i pomocnicze
    // =========================================================================

    private Station meteoStation(String id) {
        return new Station(id, "TEST", StationType.METEO, true, 300);
    }

    private Station hydroStation(String id) {
        return new Station(id, "TEST", StationType.HYDRO, true, 300);
    }

    private Warning warning(String id, WarningLevel level) {
        LocalDateTime now = LocalDateTime.now();
        return new Warning(id, level, StationType.METEO, "Zjawisko testowe",
                now, now.plusHours(6));
    }

    /**
     * Blokuje aż wszystkie wcześniej zakolejkowane zadania na EDT zostaną wykonane.
     * Konieczne bo NotificationService.publish() wywołuje listenery przez
     * SwingUtilities.invokeLater — bez tego asercje wykonałyby się przed dostarczeniem zdarzenia.
     */
    private void flushEdt() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "EDT nie zdążył przetworzyć zadań w czasie");
    }
}