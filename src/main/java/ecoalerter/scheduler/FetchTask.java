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
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Jedno zadanie cykliczne schedulera — pobiera i zapisuje dane dla jednej stacji.
 *
 * Uruchamiane cyklicznie przez TaskSchedulerManager w wątku puli. Obsługuje
 * zarówno stacje METEO jak i HYDRO na podstawie Station.getType().
 *
 * Wszystkie wyjątki są łapane wewnątrz run() — zadanie nigdy nie zabija wątku
 * puli ScheduledExecutorService. Błąd jest logowany i zgłaszany przez listener.
 *
 * Wyniki są przekazywane przez FetchListener — TaskSchedulerManager
 * może rejestrować odbiorców do aktualizacji GUI, statystyk itp.
 */
public class FetchTask implements Runnable {

    private static final Logger log = AppLogger.get(FetchTask.class);

    private final Station          station;
    private final MeteoApiService  meteoService;
    private final HydroApiService  hydroService;
    private final DataRepository   repository;
    private final DataTypeConfig   dataTypeConfig;
    private final List<FetchListener> listeners;

    // -------------------------------------------------------------------------
    // Interfejs wynikowy
    // -------------------------------------------------------------------------

    /**
     * Odbiornik wyników cyklu pobierania danych.
     * Wywoływany zawsze po zakończeniu zadania — czy zakończone sukcesem czy błędem.
     */
    public interface FetchListener {

        /**
         * Wywoływany po pomyślnym pobraniu i zapisaniu danych.
         *
         * @param station stacja, dla której pobrano dane
         */
        void onSuccess(Station station);

        /**
         * Wywoływany gdy pobranie lub zapis danych zakończyły się błędem.
         *
         * @param station      stacja, dla której wystąpił błąd
         * @param errorMessage opis błędu
         */
        void onError(Station station, String errorMessage);
    }

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    /**
     * @param station        stacja, dla której zadanie pobiera dane
     * @param meteoService   serwis API meteo (wymagany dla stacji METEO)
     * @param hydroService   serwis API hydro (wymagany dla stacji HYDRO)
     * @param repository     repozytorium do zapisu danych
     * @param dataTypeConfig konfiguracja zakresu zbieranych danych
     */
    public FetchTask(Station station,
                     MeteoApiService meteoService,
                     HydroApiService hydroService,
                     DataRepository repository,
                     DataTypeConfig dataTypeConfig) {
        this.station        = station;
        this.meteoService   = meteoService;
        this.hydroService   = hydroService;
        this.repository     = repository;
        this.dataTypeConfig = dataTypeConfig;
        this.listeners      = new ArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Zarządzanie listenerami
    // -------------------------------------------------------------------------

    /**
     * Rejestruje odbiorcę wyników tego zadania.
     *
     * @param listener odbiorca do zarejestrowania
     */
    public void addListener(FetchListener listener) {
        if (listener != null) listeners.add(listener);
    }

    // -------------------------------------------------------------------------
    // Logika główna
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        if (!station.isActive()) {
            log.debug("Stacja {} jest nieaktywna — pomijam cykl", station.getId());
            return;
        }

        long startMs = System.currentTimeMillis();
        log.debug("Start cyklu: {} [{}]", station.getId(), station.getType());

        try {
            if (station.getType() == StationType.METEO) {
                runMeteo();
            } else {
                runHydro();
            }

            long durationMs = System.currentTimeMillis() - startMs;
            AppLogger.logFetchCycle(station.getId(), station.getType().name(),
                    true, durationMs, null);

            notifySuccess();

        } catch (ApiException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            String msg = "Błąd API [HTTP " + e.getHttpStatusCode() + "]: " + e.getMessage();
            log.error("Błąd pobierania danych dla stacji {}: {}", station.getId(), msg);
            AppLogger.logFetchCycle(station.getId(), station.getType().name(),
                    false, durationMs, msg);
            notifyError(msg);

        } catch (PersistenceException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            String msg = "Błąd zapisu: " + e.getMessage();
            log.error("Błąd persystencji dla stacji {}: {}", station.getId(), msg);
            AppLogger.logFetchCycle(station.getId(), station.getType().name(),
                    false, durationMs, msg);
            notifyError(msg);

        } catch (Exception e) {
            // Łapiemy wszystko — wyjątek nie może zabić wątku puli
            long durationMs = System.currentTimeMillis() - startMs;
            String msg = "Nieoczekiwany błąd: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Nieoczekiwany błąd w cyklu stacji {}: ", station.getId(), e);
            AppLogger.logFetchCycle(station.getId(), station.getType().name(),
                    false, durationMs, msg);
            notifyError(msg);
        }
    }

    // -------------------------------------------------------------------------
    // Pobieranie meteo
    // -------------------------------------------------------------------------

    private void runMeteo() throws ApiException, PersistenceException {
        if (!dataTypeConfig.isMeteoEnabled()) {
            log.debug("Dane meteo wyłączone w konfiguracji — pomijam stację {}", station.getId());
            return;
        }

        Optional<MeteoData> dataOpt = meteoService.fetchById(station.getId());

        if (dataOpt.isEmpty()) {
            log.warn("Brak danych meteo dla stacji {} (404 lub pusta odpowiedź)", station.getId());
            return;
        }

        MeteoData data = dataOpt.get();

        // Filtrowanie pól wg DataTypeConfig — nadpisz nullami wyłączone pomiary
        if (!dataTypeConfig.isTemperatureEnabled())   data.setTemperature(null);
        if (!dataTypeConfig.isWindEnabled())           data.setWindSpeed(null);
        if (!dataTypeConfig.isPrecipitationEnabled())  data.setPrecipitation(null);

        if (!data.hasAnyMeasurement()) {
            log.debug("Wszystkie pola meteo wyłączone — pomijam zapis dla {}", station.getId());
            return;
        }

        repository.saveMeteo(data);
        log.info("Zapisano meteo: {}", data.toDisplayString());
    }

    // -------------------------------------------------------------------------
    // Pobieranie hydro
    // -------------------------------------------------------------------------

    private void runHydro() throws ApiException, PersistenceException {
        if (!dataTypeConfig.isHydroEnabled()) {
            log.debug("Dane hydro wyłączone w konfiguracji — pomijam stację {}", station.getId());
            return;
        }

        Optional<HydroData> dataOpt = hydroService.fetchById(station.getId());

        if (dataOpt.isEmpty()) {
            log.warn("Brak danych hydro dla stacji {} (404 lub pusta odpowiedź)", station.getId());
            return;
        }

        HydroData data = dataOpt.get();

        // Filtrowanie pól wg DataTypeConfig
        if (!dataTypeConfig.isWaterLevelEnabled())       data.setWaterLevel(null);
        if (!dataTypeConfig.isWaterTemperatureEnabled()) data.setWaterTemperature(null);

        if (!data.hasAnyMeasurement()) {
            log.debug("Wszystkie pola hydro wyłączone — pomijam zapis dla {}", station.getId());
            return;
        }

        repository.saveHydro(data);
        log.info("Zapisano hydro: {}", data.toDisplayString());
    }

    // -------------------------------------------------------------------------
    // Powiadamianie listenerów
    // -------------------------------------------------------------------------

    private void notifySuccess() {
        for (FetchListener listener : listeners) {
            try {
                listener.onSuccess(station);
            } catch (Exception e) {
                log.warn("Błąd w FetchListener.onSuccess: {}", e.getMessage());
            }
        }
    }

    private void notifyError(String errorMessage) {
        for (FetchListener listener : listeners) {
            try {
                listener.onError(station, errorMessage);
            } catch (Exception e) {
                log.warn("Błąd w FetchListener.onError: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Getter
    // -------------------------------------------------------------------------

    public Station getStation() {
        return station;
    }
}