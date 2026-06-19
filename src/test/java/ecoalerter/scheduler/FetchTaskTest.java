package ecoalerter.scheduler;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe FetchTask.
 * Weryfikują logikę cyklu pobierania, filtrowania pól wg DataTypeConfig
 * oraz poprawność powiadamiania listenerów.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FetchTaskTest {

    @Mock MeteoApiService          meteoService;
    @Mock HydroApiService          hydroService;
    @Mock DataRepository           repository;
    @Mock FetchTask.FetchListener  listener;

    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 14, 12, 0);

    // -------------------------------------------------------------------------
    // Scenariusze sukcesu — METEO
    // -------------------------------------------------------------------------

    @Test
    void run_meteoStation_fetchesById_andSaves() throws Exception {
        MeteoData data = fullMeteoData();
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(data));

        FetchTask task = meteoTask("12200", allEnabled());
        task.run();

        verify(meteoService).fetchById("12200");
        verify(repository).saveMeteo(data);
    }

    @Test
    void run_meteoStation_callsListenerOnSuccess() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));

        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(listener);
        task.run();

        verify(listener).onSuccess(any(Station.class));
        verify(listener, never()).onError(any(), anyString());
    }

    @Test
    void run_meteoStation_doesNotCallHydroService() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));

        meteoTask("12200", allEnabled()).run();

        verifyNoInteractions(hydroService);
    }

    // -------------------------------------------------------------------------
    // Scenariusze sukcesu — HYDRO
    // -------------------------------------------------------------------------

    @Test
    void run_hydroStation_fetchesById_andSaves() throws Exception {
        HydroData data = fullHydroData();
        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(data));

        FetchTask task = hydroTask("150180180", allEnabled());
        task.run();

        verify(hydroService).fetchById("150180180");
        verify(repository).saveHydro(data);
    }

    @Test
    void run_hydroStation_callsListenerOnSuccess() throws Exception {
        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(fullHydroData()));

        FetchTask task = hydroTask("150180180", allEnabled());
        task.addListener(listener);
        task.run();

        verify(listener).onSuccess(any(Station.class));
        verify(listener, never()).onError(any(), anyString());
    }

    @Test
    void run_hydroStation_doesNotCallMeteoService() throws Exception {
        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(fullHydroData()));

        hydroTask("150180180", allEnabled()).run();

        verifyNoInteractions(meteoService);
    }

    // -------------------------------------------------------------------------
    // Stacja nieaktywna
    // -------------------------------------------------------------------------

    @Test
    void run_inactiveStation_doesNotCallApiOrRepository() throws Exception {
        Station inactive = new Station("12200", "WARSZAWA", StationType.METEO, false, 300);
        FetchTask task = new FetchTask(inactive, meteoService, hydroService,
                repository, allEnabled());
        task.run();

        verifyNoInteractions(meteoService);
        verifyNoInteractions(hydroService);
        verifyNoInteractions(repository);
    }

    @Test
    void run_inactiveStation_doesNotCallListeners() {
        Station inactive = new Station("12200", "WARSZAWA", StationType.METEO, false, 300);
        FetchTask task = new FetchTask(inactive, meteoService, hydroService,
                repository, allEnabled());
        task.addListener(listener);
        task.run();

        verifyNoInteractions(listener);
    }

    // -------------------------------------------------------------------------
    // DataTypeConfig — wyłączenie całej kategorii
    // -------------------------------------------------------------------------

    @Test
    void run_meteoDisabledInConfig_doesNotCallMeteoService() throws Exception {
        DataTypeConfig cfg = allEnabled();
        cfg.setMeteoEnabled(false);

        meteoTask("12200", cfg).run();

        verifyNoInteractions(meteoService);
        verifyNoInteractions(repository);
    }

    @Test
    void run_hydroDisabledInConfig_doesNotCallHydroService() throws Exception {
        DataTypeConfig cfg = allEnabled();
        cfg.setHydroEnabled(false);

        hydroTask("150180180", cfg).run();

        verifyNoInteractions(hydroService);
        verifyNoInteractions(repository);
    }

    // -------------------------------------------------------------------------
    // DataTypeConfig — wyłączenie poszczególnych pól
    // -------------------------------------------------------------------------

    @Test
    void run_temperatureDisabled_setsTemperatureToNull_beforeSave() throws Exception {
        DataTypeConfig cfg = allEnabled();
        cfg.setTemperatureEnabled(false);

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));
        ArgumentCaptor<MeteoData> captor = ArgumentCaptor.forClass(MeteoData.class);

        meteoTask("12200", cfg).run();

        verify(repository).saveMeteo(captor.capture());
        assertNull(captor.getValue().getTemperature(),
                "Temperatura powinna być null gdy wyłączona w konfiguracji");
    }

    @Test
    void run_windDisabled_setsWindSpeedToNull_beforeSave() throws Exception {
        DataTypeConfig cfg = allEnabled();
        cfg.setWindEnabled(false);

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));
        ArgumentCaptor<MeteoData> captor = ArgumentCaptor.forClass(MeteoData.class);

        meteoTask("12200", cfg).run();

        verify(repository).saveMeteo(captor.capture());
        assertNull(captor.getValue().getWindSpeed());
    }

    @Test
    void run_allMeteoFieldsDisabled_doesNotSave() throws Exception {
        DataTypeConfig cfg = allEnabled();
        cfg.setTemperatureEnabled(false);
        cfg.setWindEnabled(false);
        cfg.setPrecipitationEnabled(false);
        cfg.setPressureEnabled(false);

        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));

        meteoTask("12200", cfg).run();

        verify(repository, never()).saveMeteo(any());
    }

    @Test
    void run_waterLevelDisabled_setsWaterLevelToNull_beforeSave() throws Exception {
        DataTypeConfig cfg = allEnabled();
        cfg.setWaterLevelEnabled(false);

        when(hydroService.fetchById("150180180")).thenReturn(Optional.of(fullHydroData()));
        ArgumentCaptor<HydroData> captor = ArgumentCaptor.forClass(HydroData.class);

        hydroTask("150180180", cfg).run();

        verify(repository).saveHydro(captor.capture());
        assertNull(captor.getValue().getWaterLevel());
    }

    // -------------------------------------------------------------------------
    // Stacja nie istnieje w API (404)
    // -------------------------------------------------------------------------

    @Test
    void run_meteoStation_notFound_doesNotSave() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.empty());

        meteoTask("12200", allEnabled()).run();

        verify(repository, never()).saveMeteo(any());
    }

    @Test
    void run_meteoStation_notFound_listenerStillCalledOnSuccess() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.empty());

        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(listener);
        task.run();

        // Brak danych (404) to nie błąd — stacja odpowiedziała, tylko brak pomiaru
        verify(listener).onSuccess(any());
    }

    // -------------------------------------------------------------------------
    // Obsługa błędów — wyjątki nie mogą przerwać wątku puli
    // -------------------------------------------------------------------------

    @Test
    void run_apiException_doesNotThrow() throws Exception {
        when(meteoService.fetchById("12200"))
                .thenThrow(new ApiException("timeout"));

        FetchTask task = meteoTask("12200", allEnabled());
        assertDoesNotThrow(task::run);
    }

    @Test
    void run_apiException_callsListenerOnError() throws Exception {
        when(meteoService.fetchById("12200"))
                .thenThrow(new ApiException("timeout"));

        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(listener);
        task.run();

        verify(listener).onError(any(Station.class), anyString());
        verify(listener, never()).onSuccess(any());
    }

    @Test
    void run_httpApiException_errorMessageContainsStatusCode() throws Exception {
        when(meteoService.fetchById("12200"))
                .thenThrow(new ApiException("not found", 404));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(listener);
        task.run();

        verify(listener).onError(any(), msgCaptor.capture());
        assertTrue(msgCaptor.getValue().contains("404"),
                "Komunikat błędu powinien zawierać kod HTTP");
    }

    @Test
    void run_persistenceException_doesNotThrow() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));
        doThrow(new PersistenceException("disk full"))
                .when(repository).saveMeteo(any());

        assertDoesNotThrow(() -> meteoTask("12200", allEnabled()).run());
    }

    @Test
    void run_persistenceException_callsListenerOnError() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));
        doThrow(new PersistenceException("disk full"))
                .when(repository).saveMeteo(any());

        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(listener);
        task.run();

        verify(listener).onError(any(Station.class), anyString());
    }

    @Test
    void run_unexpectedException_doesNotThrow() throws Exception {
        when(meteoService.fetchById("12200"))
                .thenThrow(new RuntimeException("unexpected"));

        assertDoesNotThrow(() -> meteoTask("12200", allEnabled()).run());
    }

    @Test
    void run_unexpectedException_callsListenerOnError() throws Exception {
        when(meteoService.fetchById("12200"))
                .thenThrow(new RuntimeException("unexpected"));

        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(listener);
        task.run();

        verify(listener).onError(any(), anyString());
    }

    // -------------------------------------------------------------------------
    // Listener — krawędziowe przypadki
    // -------------------------------------------------------------------------

    @Test
    void addListener_null_isIgnoredSilently() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));
        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(null);

        assertDoesNotThrow(task::run);
    }

    @Test
    void run_listenerThrowsException_doesNotStopOtherListeners() throws Exception {
        when(meteoService.fetchById("12200")).thenReturn(Optional.of(fullMeteoData()));

        FetchTask.FetchListener badListener  = mock(FetchTask.FetchListener.class);
        FetchTask.FetchListener goodListener = mock(FetchTask.FetchListener.class);
        doThrow(new RuntimeException("listener crash")).when(badListener).onSuccess(any());

        FetchTask task = meteoTask("12200", allEnabled());
        task.addListener(badListener);
        task.addListener(goodListener);
        task.run();

        verify(goodListener).onSuccess(any());
    }

    // -------------------------------------------------------------------------
    // getStation()
    // -------------------------------------------------------------------------

    @Test
    void getStation_returnsInjectedStation() {
        Station station = new Station("12200", "WARSZAWA", StationType.METEO, true, 300);
        FetchTask task  = new FetchTask(station, meteoService, hydroService,
                repository, allEnabled());

        assertSame(station, task.getStation());
    }

    // =========================================================================
    // Fabryki
    // =========================================================================

    private FetchTask meteoTask(String stationId, DataTypeConfig cfg) {
        Station s = new Station(stationId, "TEST", StationType.METEO, true, 300);
        return new FetchTask(s, meteoService, hydroService, repository, cfg);
    }

    private FetchTask hydroTask(String stationId, DataTypeConfig cfg) {
        Station s = new Station(stationId, "TEST", StationType.HYDRO, true, 300);
        return new FetchTask(s, meteoService, hydroService, repository, cfg);
    }

    private MeteoData fullMeteoData() {
        return new MeteoData("12200", "WARSZAWA", NOW, 22.4, 3.1, 0.0, 1013.2);
    }

    private HydroData fullHydroData() {
        return new HydroData("150180180", "Warszawa", "Wisła", NOW, 145.0, 14.5);
    }

    private DataTypeConfig allEnabled() {
        return new DataTypeConfig(); // domyślnie wszystko włączone
    }
}