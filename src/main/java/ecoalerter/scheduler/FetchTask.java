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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Jedno zadanie cykliczne schedulera — pobiera i zapisuje dane dla jednej stacji.
 *
 * <h2>Znacznik czasu pobrania</h2>
 * Tuż przed zapisem każdego rekordu ustawiamy {@code fetchedAt = now()}.
 * Pole {@code timestamp} (czas pomiaru po stronie IMGW) zostaje nietknięte —
 * jest ustawione przez parser API. Różnica między oboma znacznikami pokazuje
 * opóźnienie między pomiarem a jego pobraniem.
 *
 * <h2>Auto-update nazwy z API (apiName)</h2>
 * Po każdym pomyślnym pobraniu, jeśli {@code stationName} z API różni się
 * od zapisanego {@link Station#getApiName()}, aktualizujemy stację w repo.
 * Dzięki temu kolumna „Nazwa stacji" w GUI sama się wypełnia po pierwszym
 * fetchu — użytkownik dodaje stację po samym ID, a poprawna nazwa
 * (np. „WARSZAWA-BIELANY" zamiast tymczasowej nazwy własnej) pojawia się
 * sama po pierwszym cyklu schedulera.
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

    /**
     * Rejestruje odbiorcę wyników tego zadania.
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
            long durationMs = System.currentTimeMillis() - startMs;
            String msg = "Nieoczekiwany błąd: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("Nieoczekiwany błąd w cyklu stacji {}: ", station.getId(), e);
            AppLogger.logFetchCycle(station.getId(), station.getType().name(),
                    false, durationMs, msg);
            notifyError(msg);
        }
    }

    /**
     * Pojedynczy cykl pobierania danych meteo dla stacji obsługiwanej przez ten FetchTask.
     */
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

        if (!data.hasAnyMeasurement()) {
            log.debug("API nie zwróciło żadnych pomiarów meteo dla {} — pomijam zapis",
                    station.getId());
            return;
        }

        data.setFetchedAt(LocalDateTime.now());
        repository.saveMeteo(data);
        log.info("Zapisano meteo: {}", data.toDisplayString());

        maybeUpdateApiName(data.getStationName());
    }

    /**
     * Pojedynczy cykl pobierania danych hydro dla stacji obsługiwanej przez ten FetchTask.
     */
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

        if (!data.hasAnyMeasurement()) {
            log.debug("API nie zwróciło żadnych pomiarów hydro dla {} — pomijam zapis",
                    station.getId());
            return;
        }

        data.setFetchedAt(LocalDateTime.now());
        repository.saveHydro(data);
        log.info("Zapisano hydro: {}", data.toDisplayString());

        maybeUpdateApiName(data.getStationName());
    }

    /**
     * Aktualizuje {@link Station#setApiName(String)} jeśli API zwróciło inną
     * nazwę niż dotychczas zapisana — typowo to się dzieje przy pierwszym
     * cyklu schedulera dla nowo dodanej stacji (gdzie apiName było puste lub
     * równe nazwie własnej wpisanej przez użytkownika).
     *
     * Zapis stacji jest robiony jako osobna operacja (nie w jednej transakcji
     * z {@code saveMeteo}/{@code saveHydro}) — repository nie ma API
     * transakcyjnego dla operacji crossowych, a tu pojedynczy upsert stacji
     * jest atomowy sam w sobie.
     *
     * @param fetchedApiName nazwa zwrócona przez API w bieżącym cyklu
     */
    private void maybeUpdateApiName(String fetchedApiName) {
        if (fetchedApiName == null || fetchedApiName.isBlank()) return;
        if (Objects.equals(station.getApiName(), fetchedApiName)) return;

        String previous = station.getApiName();
        station.setApiName(fetchedApiName);

        try {
            repository.saveStation(station);
            log.debug("Zaktualizowano apiName stacji {} -> '{}' (poprzednio: '{}')",
                    station.getId(), fetchedApiName, previous);
        } catch (PersistenceException e) {
            // Nie chcemy żeby błąd zapisu nazwy zawalił cały cykl — pomiary
            // są już bezpiecznie zapisane. Wycofujemy zmianę w pamięci żeby
            // następny cykl spróbował znów.
            station.setApiName(previous);
            log.warn("Nie udało się zaktualizować apiName dla stacji {}: {}",
                    station.getId(), e.getMessage());
        }
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

    public Station getStation() {
        return station;
    }
}