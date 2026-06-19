package ecoalerter.service;

import ecoalerter.config.AppConfig;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.scheduler.TaskSchedulerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe StationService.
 * Repository i scheduler są mockowane — testowana jest logika koordynująca,
 * a nie rzeczywiste zapisy czy harmonogram.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StationServiceTest {

    @Mock DataRepository       repository;
    @Mock TaskSchedulerManager scheduler;
    @Mock AppConfig            config;

    private StationService service;

    @BeforeEach
    void setUp() {
        service = new StationService(repository, scheduler, config);
    }

    // -------------------------------------------------------------------------
    // addStation
    // -------------------------------------------------------------------------

    @Test
    void addStation_activeStation_savesAndSchedules() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");

        service.addStation(station);

        verify(repository).saveStation(station);
        verify(scheduler).scheduleStation(station);
    }

    @Test
    void addStation_inactiveStation_savesButDoesNotSchedule() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        station.setActive(false);

        service.addStation(station);

        verify(repository).saveStation(station);
        verify(scheduler, never()).scheduleStation(any());
    }

    @Test
    void addStation_nullStation_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.addStation(null));
    }

    @Test
    void addStation_nullId_throwsIllegalArgumentException() {
        Station station = new Station(null, "WARSZAWA", StationType.METEO);

        assertThrows(IllegalArgumentException.class, () -> service.addStation(station));
    }

    @Test
    void addStation_nullId_doesNotCallRepository() {
        Station station = new Station(null, "WARSZAWA", StationType.METEO);

        assertThrows(IllegalArgumentException.class, () -> service.addStation(station));
        verifyNoInteractions(repository);
    }

    @Test
    void addStation_repositoryThrows_propagatesException() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        doThrow(new PersistenceException("disk full")).when(repository).saveStation(station);

        assertThrows(PersistenceException.class, () -> service.addStation(station));
    }

    @Test
    void addStation_repositoryThrows_doesNotScheduleAnyway() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        doThrow(new PersistenceException("disk full")).when(repository).saveStation(station);

        assertThrows(PersistenceException.class, () -> service.addStation(station));
        verify(scheduler, never()).scheduleStation(any());
    }

    // -------------------------------------------------------------------------
    // removeStation
    // -------------------------------------------------------------------------

    @Test
    void removeStation_unschedulesAndDeletes() throws PersistenceException {
        service.removeStation("12200", StationType.METEO);

        verify(scheduler).unscheduleStation("12200");
        verify(repository).deleteStation("12200", StationType.METEO);
    }

    @Test
    void removeStation_unschedulesBeforeDeleting() throws PersistenceException {
        var inOrder = inOrder(scheduler, repository);

        service.removeStation("12200", StationType.METEO);

        inOrder.verify(scheduler).unscheduleStation("12200");
        inOrder.verify(repository).deleteStation("12200", StationType.METEO);
    }

    // -------------------------------------------------------------------------
    // activateStation
    // -------------------------------------------------------------------------

    @Test
    void activateStation_existingStation_setsActiveAndSchedules() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        station.setActive(false);
        when(repository.findAllStations()).thenReturn(List.of(station));

        service.activateStation("12200", StationType.METEO);

        assertTrue(station.isActive());
        verify(repository).saveStation(station);
        verify(scheduler).scheduleStation(station);
    }

    @Test
    void activateStation_nonExistentStation_doesNothing() throws PersistenceException {
        when(repository.findAllStations()).thenReturn(List.of());

        service.activateStation("99999", StationType.METEO);

        verify(repository, never()).saveStation(any());
        verify(scheduler, never()).scheduleStation(any());
    }

    @Test
    void activateStation_wrongType_doesNothing() throws PersistenceException {
        Station hydroStation = new Station("12200", "WARSZAWA", StationType.HYDRO, false, 300);
        when(repository.findAllStations()).thenReturn(List.of(hydroStation));

        service.activateStation("12200", StationType.METEO); // szukamy METEO, jest HYDRO

        verify(repository, never()).saveStation(any());
    }

    // -------------------------------------------------------------------------
    // deactivateStation
    // -------------------------------------------------------------------------

    @Test
    void deactivateStation_existingStation_setsInactiveAndUnschedules() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        when(repository.findAllStations()).thenReturn(List.of(station));

        service.deactivateStation("12200", StationType.METEO);

        assertFalse(station.isActive());
        verify(repository).saveStation(station);
        verify(scheduler).unscheduleStation("12200");
    }

    @Test
    void deactivateStation_nonExistentStation_doesNothing() throws PersistenceException {
        when(repository.findAllStations()).thenReturn(List.of());

        service.deactivateStation("99999", StationType.METEO);

        verify(repository, never()).saveStation(any());
        verify(scheduler, never()).unscheduleStation(any());
    }

    // -------------------------------------------------------------------------
    // updateInterval
    // -------------------------------------------------------------------------

    @Test
    void updateInterval_activeStation_savesAndReschedules() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        when(repository.findAllStations()).thenReturn(List.of(station));

        service.updateInterval("12200", StationType.METEO, 600);

        assertEquals(600, station.getIntervalSeconds());
        verify(repository).saveStation(station);
        verify(scheduler).rescheduleStation(station, 600);
    }

    @Test
    void updateInterval_inactiveStation_savesButDoesNotReschedule() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        station.setActive(false);
        when(repository.findAllStations()).thenReturn(List.of(station));

        service.updateInterval("12200", StationType.METEO, 600);

        verify(repository).saveStation(station);
        verify(scheduler, never()).rescheduleStation(any(), anyInt());
    }

    @Test
    void updateInterval_belowMinimum_clampsTo60() throws PersistenceException {
        Station station = activeMeteo("12200", "WARSZAWA");
        when(repository.findAllStations()).thenReturn(List.of(station));

        service.updateInterval("12200", StationType.METEO, 10);

        assertEquals(60, station.getIntervalSeconds());
        verify(scheduler).rescheduleStation(station, 60);
    }

    @Test
    void updateInterval_nonExistentStation_doesNothing() throws PersistenceException {
        when(repository.findAllStations()).thenReturn(List.of());

        service.updateInterval("99999", StationType.METEO, 600);

        verify(repository, never()).saveStation(any());
        verify(scheduler, never()).rescheduleStation(any(), anyInt());
    }

    // -------------------------------------------------------------------------
    // getAllStations / getActiveByType
    // -------------------------------------------------------------------------

    @Test
    void getAllStations_delegatesToRepository() throws PersistenceException {
        List<Station> expected = List.of(activeMeteo("12200", "WARSZAWA"));
        when(repository.findAllStations()).thenReturn(expected);

        List<Station> result = service.getAllStations();

        assertSame(expected, result);
    }

    @Test
    void getActiveByType_delegatesToRepository() throws PersistenceException {
        List<Station> expected = List.of(activeMeteo("12200", "WARSZAWA"));
        when(repository.findActiveStations(StationType.METEO)).thenReturn(expected);

        List<Station> result = service.getActiveByType(StationType.METEO);

        assertSame(expected, result);
        verify(repository).findActiveStations(StationType.METEO);
    }

    // -------------------------------------------------------------------------
    // exists
    // -------------------------------------------------------------------------

    @Test
    void exists_stationPresent_returnsTrue() throws PersistenceException {
        when(repository.findAllStations()).thenReturn(List.of(activeMeteo("12200", "WARSZAWA")));

        assertTrue(service.exists("12200", StationType.METEO));
    }

    @Test
    void exists_stationAbsent_returnsFalse() throws PersistenceException {
        when(repository.findAllStations()).thenReturn(List.of());

        assertFalse(service.exists("99999", StationType.METEO));
    }

    @Test
    void exists_sameIdDifferentType_returnsFalse() throws PersistenceException {
        Station hydroStation = new Station("12200", "X", StationType.HYDRO, true, 300);
        when(repository.findAllStations()).thenReturn(List.of(hydroStation));

        assertFalse(service.exists("12200", StationType.METEO));
    }

    // -------------------------------------------------------------------------
    // scheduleAllActive
    // -------------------------------------------------------------------------

    @Test
    void scheduleAllActive_callsSchedulerStartAllWithAllStations() throws PersistenceException {
        List<Station> all = List.of(
                activeMeteo("12200", "WARSZAWA"),
                activeMeteo("12385", "KRAKOW")
        );
        when(repository.findAllStations()).thenReturn(all);

        service.scheduleAllActive();

        verify(scheduler).startAll(all);
    }

    @Test
    void scheduleAllActive_emptyRepository_callsStartAllWithEmptyList() throws PersistenceException {
        when(repository.findAllStations()).thenReturn(List.of());

        service.scheduleAllActive();

        verify(scheduler).startAll(List.of());
    }

    // =========================================================================
    // Fabryki
    // =========================================================================

    private Station activeMeteo(String id, String name) {
        return new Station(id, name, StationType.METEO, true, 300);
    }
}