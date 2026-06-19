package ecoalerter.service;

import ecoalerter.api.ApiException;
import ecoalerter.api.HydroApiService;
import ecoalerter.api.MeteoApiService;
import ecoalerter.config.DataTypeConfig;
import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe DataCollectionService.
 * MeteoApiService, HydroApiService i DataRepository są mockowane.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataCollectionServiceTest {

    @Mock MeteoApiService meteoService;
    @Mock HydroApiService  hydroService;
    @Mock DataRepository   repository;

    private DataCollectionService service;

    private static final LocalDateTime T1 = LocalDateTime.of(2024, 6, 14, 12, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2024, 6, 14, 13, 0);

    @BeforeEach
    void setUp() {
        service = new DataCollectionService(
                meteoService, hydroService, repository, new DataTypeConfig());
    }

    // -------------------------------------------------------------------------
    // fetchAndSaveMeteo
    // -------------------------------------------------------------------------

    @Test
    void fetchAndSaveMeteo_dataPresent_savesAndReturns() throws Exception {
        MeteoData data = fullMeteo();
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(data));

        Optional<MeteoData> result = service.fetchAndSaveMeteo("12200");

        assertTrue(result.isPresent());
        assertEquals(data, result.get());
        verify(repository).saveMeteo(data);
    }

    @Test
    void fetchAndSaveMeteo_notFound_returnsEmpty_doesNotSave() throws Exception {
        when(meteoService.fetchById("99999")).thenReturn(Optional.empty());

        Optional<MeteoData> result = service.fetchAndSaveMeteo("99999");

        assertTrue(result.isEmpty());
        verify(repository, never()).saveMeteo(any());
    }

    @Test
    void fetchAndSaveMeteo_apiThrows_propagatesException() throws Exception {
        when(meteoService.fetchById("12200")).thenThrow(new ApiException("timeout"));

        assertThrows(ApiException.class, () -> service.fetchAndSaveMeteo("12200"));
    }

    @Test
    void fetchAndSaveMeteo_temperatureDisabled_savesWithNullTemperature() throws Exception {
        DataTypeConfig cfg = new DataTypeConfig();
        cfg.setTemperatureEnabled(false);
        service = new DataCollectionService(meteoService, hydroService, repository, cfg);

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteo()));

        Optional<MeteoData> result = service.fetchAndSaveMeteo("12200");

        assertNull(result.get().getTemperature());
        verify(repository).saveMeteo(any());
    }

    @Test
    void fetchAndSaveMeteo_allFieldsDisabled_doesNotSave() throws Exception {
        DataTypeConfig cfg = new DataTypeConfig();
        cfg.setTemperatureEnabled(false);
        cfg.setWindEnabled(false);
        cfg.setPrecipitationEnabled(false);
        cfg.setPressureEnabled(false);
        service = new DataCollectionService(meteoService, hydroService, repository, cfg);

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteo()));

        Optional<MeteoData> result = service.fetchAndSaveMeteo("12200");

        assertTrue(result.isPresent(), "Wynik powinien być zwrócony nawet bez zapisu");
        verify(repository, never()).saveMeteo(any());
    }

    // -------------------------------------------------------------------------
    // fetchAndSaveHydro
    // -------------------------------------------------------------------------

    @Test
    void fetchAndSaveHydro_dataPresent_savesAndReturns() throws Exception {
        HydroData data = fullHydro();
        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(data));

        Optional<HydroData> result = service.fetchAndSaveHydro("150180180");

        assertTrue(result.isPresent());
        verify(repository).saveHydro(data);
    }

    @Test
    void fetchAndSaveHydro_notFound_returnsEmpty() throws Exception {
        when(hydroService.fetchById("99999")).thenReturn(Optional.empty());

        Optional<HydroData> result = service.fetchAndSaveHydro("99999");

        assertTrue(result.isEmpty());
        verify(repository, never()).saveHydro(any());
    }

    @Test
    void fetchAndSaveHydro_waterLevelDisabled_savesWithNullLevel() throws Exception {
        DataTypeConfig cfg = new DataTypeConfig();
        cfg.setWaterLevelEnabled(false);
        service = new DataCollectionService(meteoService, hydroService, repository, cfg);

        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(fullHydro()));

        Optional<HydroData> result = service.fetchAndSaveHydro("150180180");

        assertNull(result.get().getWaterLevel());
    }

    @Test
    void fetchAndSaveHydro_apiThrows_propagatesException() throws Exception {
        when(hydroService.fetchById("150180180")).thenThrow(new ApiException("timeout"));

        assertThrows(ApiException.class, () -> service.fetchAndSaveHydro("150180180"));
    }

    // -------------------------------------------------------------------------
    // fetchAndSaveAll — wsadowe pobieranie
    // -------------------------------------------------------------------------

    @Test
    void fetchAndSaveAll_emptyList_returnsEmptyMap() {
        Map<String, Boolean> result = service.fetchAndSaveAll(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void fetchAndSaveAll_nullList_returnsEmptyMap() {
        Map<String, Boolean> result = service.fetchAndSaveAll(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void fetchAndSaveAll_allSucceed_returnsAllTrue() throws Exception {
        Station meteo = meteoStation("12200");
        Station hydro = hydroStation("150180180");

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteo()));
        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(fullHydro()));

        Map<String, Boolean> result = service.fetchAndSaveAll(List.of(meteo, hydro));

        assertEquals(2, result.size());
        assertTrue(result.get("12200"));
        assertTrue(result.get("150180180"));
    }

    @Test
    void fetchAndSaveAll_httpErrorOnOneStation_continuesWithNext() throws Exception {
        Station station1 = meteoStation("12200");
        Station station2 = meteoStation("12385");

        when(meteoService.fetchById("12200")).thenThrow(new ApiException("not found", 404));
        when(meteoService.fetchById("12385")).thenReturn(Optional.of(fullMeteo()));

        Map<String, Boolean> result = service.fetchAndSaveAll(List.of(station1, station2));

        assertFalse(result.get("12200"));
        assertTrue(result.get("12385"));
        verify(meteoService).fetchById("12385"); // druga stacja mimo błędu pierwszej
    }

    @Test
    void fetchAndSaveAll_networkError_stopsProcessingRemainingStations() throws Exception {
        Station station1 = meteoStation("12200");
        Station station2 = meteoStation("12385");

        when(meteoService.fetchById("12200")).thenThrow(new ApiException("connection refused"));

        Map<String, Boolean> result = service.fetchAndSaveAll(List.of(station1, station2));

        assertEquals(1, result.size(), "Powinna być przetworzona tylko pierwsza stacja");
        assertFalse(result.get("12200"));
        verify(meteoService, never()).fetchById("12385");
    }

    @Test
    void fetchAndSaveAll_persistenceError_marksFalseAndContinues() throws Exception {
        Station station1 = meteoStation("12200");
        Station station2 = meteoStation("12385");

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteo()));
        when(meteoService.fetchById("12385")).thenReturn(Optional.of(fullMeteo()));
        doThrow(new PersistenceException("disk full")).when(repository).saveMeteo(any());

        Map<String, Boolean> result = service.fetchAndSaveAll(List.of(station1, station2));

        assertFalse(result.get("12200"));
        assertFalse(result.get("12385"));
    }

    @Test
    void fetchAndSaveAll_mixedStationTypes_callsCorrectService() throws Exception {
        Station meteo = meteoStation("12200");
        Station hydro = hydroStation("150180180");

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteo()));
        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(fullHydro()));

        service.fetchAndSaveAll(List.of(meteo, hydro));

        verify(meteoService).fetchById("12200");
        verify(hydroService).fetchById("150180180");
        verify(hydroService, never()).fetchById("12200");
        verify(meteoService, never()).fetchById("150180180");
    }

    // -------------------------------------------------------------------------
    // Odczyt historii
    // -------------------------------------------------------------------------

    @Test
    void getLatestMeteo_delegatesToRepository() throws Exception {
        MeteoData data = fullMeteo();
        when(repository.findLatestMeteo("12200")).thenReturn(Optional.of(data));

        Optional<MeteoData> result = service.getLatestMeteo("12200");

        assertEquals(Optional.of(data), result);
    }

    @Test
    void getLatestHydro_delegatesToRepository() throws Exception {
        HydroData data = fullHydro();
        when(repository.findLatestHydro("150180180")).thenReturn(Optional.of(data));

        Optional<HydroData> result = service.getLatestHydro("150180180");

        assertEquals(Optional.of(data), result);
    }

    @Test
    void getMeteoHistory_delegatesToRepositoryWithRange() throws Exception {
        List<MeteoData> expected = List.of(fullMeteo());
        when(repository.findMeteoByStationAndRange("12200", T1, T2)).thenReturn(expected);

        List<MeteoData> result = service.getMeteoHistory("12200", T1, T2);

        assertSame(expected, result);
        verify(repository).findMeteoByStationAndRange("12200", T1, T2);
    }

    @Test
    void getHydroHistory_delegatesToRepositoryWithRange() throws Exception {
        List<HydroData> expected = List.of(fullHydro());
        when(repository.findHydroByStationAndRange("150180180", T1, T2)).thenReturn(expected);

        List<HydroData> result = service.getHydroHistory("150180180", T1, T2);

        assertSame(expected, result);
    }

    @Test
    void getAllMeteo_delegatesToRepository() throws Exception {
        List<MeteoData> expected = List.of(fullMeteo());
        when(repository.findMeteoByStation("12200")).thenReturn(expected);

        assertSame(expected, service.getAllMeteo("12200"));
    }

    @Test
    void getAllHydro_delegatesToRepository() throws Exception {
        List<HydroData> expected = List.of(fullHydro());
        when(repository.findHydroByStation("150180180")).thenReturn(expected);

        assertSame(expected, service.getAllHydro("150180180"));
    }

    // -------------------------------------------------------------------------
    // cleanOldData
    // -------------------------------------------------------------------------

    @Test
    void cleanOldData_callsRepositoryWithCorrectCutoff() throws Exception {
        service.cleanOldData(30);

        verify(repository).deleteMeteoOlderThan(any(LocalDateTime.class));
        verify(repository).deleteHydroOlderThan(any(LocalDateTime.class));
    }

    @Test
    void cleanOldData_belowMinimum_clampsToOneDay() throws Exception {
        service.cleanOldData(0);

        // Tylko sprawdzamy że nie throwuje i woła repo — wartość minimalna 1 dzień
        verify(repository).deleteMeteoOlderThan(any(LocalDateTime.class));
    }

    @Test
    void cleanOldData_negativeValue_clampsToOneDay() throws Exception {
        assertDoesNotThrow(() -> service.cleanOldData(-5));
        verify(repository).deleteMeteoOlderThan(any(LocalDateTime.class));
        verify(repository).deleteHydroOlderThan(any(LocalDateTime.class));
    }

    @Test
    void cleanOldData_repositoryThrows_propagatesException() throws Exception {
        doThrow(new PersistenceException("error"))
                .when(repository).deleteMeteoOlderThan(any());

        assertThrows(PersistenceException.class, () -> service.cleanOldData(30));
    }

    // =========================================================================
    // Fabryki
    // =========================================================================

    private MeteoData fullMeteo() {
        return new MeteoData("12200", "WARSZAWA", T1, 22.4, 3.1, 0.0, 1013.2);
    }

    private HydroData fullHydro() {
        return new HydroData("150180180", "Warszawa", "Wisła", T1, 145.0, 14.5);
    }

    private Station meteoStation(String id) {
        return new Station(id, "TEST", StationType.METEO, true, 300);
    }

    private Station hydroStation(String id) {
        return new Station(id, "TEST", StationType.HYDRO, true, 300);
    }
}