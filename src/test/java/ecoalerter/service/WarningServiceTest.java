package ecoalerter.service;

import ecoalerter.api.ApiException;
import ecoalerter.api.WarningApiService;
import ecoalerter.config.AppConfig;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe WarningService.
 * WarningApiService, DataRepository i AppConfig są mockowane.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarningServiceTest {

    @Mock WarningApiService warningApiService;
    @Mock DataRepository    repository;
    @Mock AppConfig         config;

    private WarningService service;

    @BeforeEach
    void setUp() {
        service = new WarningService(warningApiService, repository, config);
    }

    // -------------------------------------------------------------------------
    // fetchAndSave
    // -------------------------------------------------------------------------

    @Test
    void fetchAndSave_filtersByConfiguredMinLevel_andSaves() throws Exception {
        List<Warning> all      = List.of(warning("W001", WarningLevel.ORANGE));
        List<Warning> filtered = List.of(warning("W001", WarningLevel.ORANGE));

        when(warningApiService.fetchAllWarnings()).thenReturn(all);
        when(config.getWarningsFilterLevel()).thenReturn(WarningLevel.ORANGE);
        when(warningApiService.filterByMinLevel(all, WarningLevel.ORANGE)).thenReturn(filtered);

        List<Warning> result = service.fetchAndSave();

        assertEquals(filtered, result);
        verify(repository).saveAllWarnings(filtered);
    }

    @Test
    void fetchAndSave_callsDeleteExpiredWarnings() throws Exception {
        when(warningApiService.fetchAllWarnings()).thenReturn(List.of());
        when(config.getWarningsFilterLevel()).thenReturn(WarningLevel.YELLOW);
        when(warningApiService.filterByMinLevel(any(), any())).thenReturn(List.of());

        service.fetchAndSave();

        verify(repository).deleteExpiredWarnings();
    }

    @Test
    void fetchAndSave_apiThrows_propagatesException() throws Exception {
        when(warningApiService.fetchAllWarnings()).thenThrow(new ApiException("timeout"));

        assertThrows(ApiException.class, () -> service.fetchAndSave());
    }

    @Test
    void fetchAndSave_apiThrows_doesNotCallSave() throws Exception {
        when(warningApiService.fetchAllWarnings()).thenThrow(new ApiException("timeout"));

        assertThrows(ApiException.class, () -> service.fetchAndSave());
        verify(repository, never()).saveAllWarnings(any());
    }

    @Test
    void fetchAndSave_persistenceThrows_propagatesException() throws Exception {
        when(warningApiService.fetchAllWarnings()).thenReturn(List.of());
        when(config.getWarningsFilterLevel()).thenReturn(WarningLevel.YELLOW);
        when(warningApiService.filterByMinLevel(any(), any())).thenReturn(List.of());
        doThrow(new PersistenceException("disk full")).when(repository).saveAllWarnings(any());

        assertThrows(PersistenceException.class, () -> service.fetchAndSave());
    }

    @Test
    void fetchAndSave_emptyFiltered_doesNotThrowOnLogging() throws Exception {
        when(warningApiService.fetchAllWarnings()).thenReturn(List.of(warning("W001", WarningLevel.YELLOW)));
        when(config.getWarningsFilterLevel()).thenReturn(WarningLevel.RED);
        when(warningApiService.filterByMinLevel(any(), any())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.fetchAndSave());
    }

    // -------------------------------------------------------------------------
    // getActiveWarnings / getActiveWarningsByLevel
    // -------------------------------------------------------------------------

    @Test
    void getActiveWarnings_delegatesToRepository() throws Exception {
        List<Warning> expected = List.of(warning("W001", WarningLevel.RED));
        when(repository.findActiveWarnings()).thenReturn(expected);

        assertSame(expected, service.getActiveWarnings());
    }

    @Test
    void getActiveWarningsByLevel_delegatesToRepository() throws Exception {
        List<Warning> expected = List.of(warning("W001", WarningLevel.ORANGE));
        when(repository.findActiveWarningsByMinLevel(WarningLevel.ORANGE)).thenReturn(expected);

        List<Warning> result = service.getActiveWarningsByLevel(WarningLevel.ORANGE);

        assertSame(expected, result);
    }

    // -------------------------------------------------------------------------
    // getActiveWarningsForStation / getActiveWarningsByType
    // -------------------------------------------------------------------------

    @Test
    void getActiveWarningsForStation_filtersViaWarningApiService() throws Exception {
        List<Warning> all      = List.of(warning("W001", WarningLevel.ORANGE));
        List<Warning> filtered = List.of(warning("W001", WarningLevel.ORANGE));

        when(repository.findActiveWarnings()).thenReturn(all);
        when(warningApiService.filterByStation(all, "12200")).thenReturn(filtered);

        List<Warning> result = service.getActiveWarningsForStation("12200");

        assertSame(filtered, result);
    }

    @Test
    void getActiveWarningsByType_filtersViaWarningApiService() throws Exception {
        List<Warning> all      = List.of(warning("W001", WarningLevel.ORANGE));
        List<Warning> filtered = List.of(warning("W001", WarningLevel.ORANGE));

        when(repository.findActiveWarnings()).thenReturn(all);
        when(warningApiService.filterByType(all, StationType.METEO)).thenReturn(filtered);

        List<Warning> result = service.getActiveWarningsByType(StationType.METEO);

        assertSame(filtered, result);
    }

    // -------------------------------------------------------------------------
    // hasActiveRedWarnings / hasAnyActiveWarning
    // -------------------------------------------------------------------------

    @Test
    void hasActiveRedWarnings_whenRedExists_returnsTrue() throws Exception {
        when(repository.findActiveWarningsByMinLevel(WarningLevel.RED))
                .thenReturn(List.of(warning("W001", WarningLevel.RED)));

        assertTrue(service.hasActiveRedWarnings());
    }

    @Test
    void hasActiveRedWarnings_whenNoRed_returnsFalse() throws Exception {
        when(repository.findActiveWarningsByMinLevel(WarningLevel.RED)).thenReturn(List.of());

        assertFalse(service.hasActiveRedWarnings());
    }

    @Test
    void hasAnyActiveWarning_whenWarningsExist_returnsTrue() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(List.of(warning("W001", WarningLevel.YELLOW)));

        assertTrue(service.hasAnyActiveWarning());
    }

    @Test
    void hasAnyActiveWarning_whenEmpty_returnsFalse() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(List.of());

        assertFalse(service.hasAnyActiveWarning());
    }

    // -------------------------------------------------------------------------
    // getHighestActiveLevel
    // -------------------------------------------------------------------------

    @Test
    void getHighestActiveLevel_returnsHighestAmongActive() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(List.of(
                warning("W001", WarningLevel.YELLOW),
                warning("W002", WarningLevel.RED),
                warning("W003", WarningLevel.ORANGE)
        ));

        assertEquals(WarningLevel.RED, service.getHighestActiveLevel());
    }

    @Test
    void getHighestActiveLevel_whenEmpty_returnsNull() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(List.of());

        assertNull(service.getHighestActiveLevel());
    }

    @Test
    void getHighestActiveLevel_singleWarning_returnsThatLevel() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(
                List.of(warning("W001", WarningLevel.ORANGE)));

        assertEquals(WarningLevel.ORANGE, service.getHighestActiveLevel());
    }

    // -------------------------------------------------------------------------
    // getSummary
    // -------------------------------------------------------------------------

    @Test
    void getSummary_aggregatesCorrectly() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(List.of(
                warning("W001", WarningLevel.YELLOW, StationType.METEO),
                warning("W002", WarningLevel.RED,    StationType.HYDRO),
                warning("W003", WarningLevel.RED,    StationType.METEO)
        ));

        WarningService.WarningSummary summary = service.getSummary();

        assertEquals(3, summary.getTotal());
        assertEquals(1, summary.getCount(WarningLevel.YELLOW));
        assertEquals(2, summary.getCount(WarningLevel.RED));
        assertEquals(0, summary.getCount(WarningLevel.ORANGE));
        assertEquals(2, summary.getCount(StationType.METEO));
        assertEquals(1, summary.getCount(StationType.HYDRO));
        assertEquals(WarningLevel.RED, summary.getHighestLevel());
    }

    @Test
    void getSummary_empty_returnsEmptySummary() throws Exception {
        when(repository.findActiveWarnings()).thenReturn(List.of());

        WarningService.WarningSummary summary = service.getSummary();

        assertTrue(summary.isEmpty());
        assertEquals(0, summary.getTotal());
        assertNull(summary.getHighestLevel());
    }

    // -------------------------------------------------------------------------
    // cleanExpiredWarnings
    // -------------------------------------------------------------------------

    @Test
    void cleanExpiredWarnings_returnsDeletedCount() throws Exception {
        when(repository.deleteExpiredWarnings()).thenReturn(5);

        assertEquals(5, service.cleanExpiredWarnings());
    }

    @Test
    void cleanExpiredWarnings_zeroDeleted_returnsZero() throws Exception {
        when(repository.deleteExpiredWarnings()).thenReturn(0);

        assertEquals(0, service.cleanExpiredWarnings());
    }

    // =========================================================================
    // WarningSummary — testy bezpośrednie statycznej metody from()
    // =========================================================================

    @Test
    void warningSummary_from_nullLevelsAreIgnored() {
        Warning w = new Warning();
        w.setId("W999");
        w.setLevel(null);
        w.setType(null);

        WarningService.WarningSummary summary = WarningService.WarningSummary.from(List.of(w));

        assertEquals(1, summary.getTotal());
        assertEquals(0, summary.getCount(WarningLevel.YELLOW));
        assertNull(summary.getHighestLevel());
    }

    @Test
    void warningSummary_toString_containsCounts() {
        WarningService.WarningSummary summary = WarningService.WarningSummary.from(List.of(
                warning("W001", WarningLevel.RED)
        ));

        String result = summary.toString();
        assertTrue(result.contains("RED=1"));
    }

    // =========================================================================
    // Fabryki
    // =========================================================================

    private Warning warning(String id, WarningLevel level) {
        return warning(id, level, StationType.METEO);
    }

    private Warning warning(String id, WarningLevel level, StationType type) {
        LocalDateTime now = LocalDateTime.now();
        return new Warning(id, level, type, "Zjawisko testowe", now, now.plusHours(6));
    }
}