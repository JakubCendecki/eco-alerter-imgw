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
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serwis orkiestrujący pobieranie i odczyt danych pomiarowych.
 *
 * Oferuje dwa tryby pracy:
 *
 * 1. Manualne wywołanie — GUI może zlecić natychmiastowe pobranie danych
 *    dla wybranej stacji poza harmonogramem (np. przycisk "Odśwież teraz").
 *
 * 2. Odczyt historii — zwraca dane z repozytorium do wyświetlenia
 *    w tabelach i wykresach GUI.
 *
 * Czyszczenie starych danych (cleanOldData) wywołuje się z poziomie
 * TaskSchedulerManager lub ręcznie z SettingsPanel.
 */
public class DataCollectionService {

    private static final Logger log = AppLogger.get(DataCollectionService.class);

    private final MeteoApiService  meteoService;
    private final HydroApiService  hydroService;
    private final DataRepository   repository;
    private final DataTypeConfig   dataTypeConfig;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public DataCollectionService(MeteoApiService meteoService,
                                 HydroApiService hydroService,
                                 DataRepository repository,
                                 DataTypeConfig dataTypeConfig) {
        this.meteoService   = meteoService;
        this.hydroService   = hydroService;
        this.repository     = repository;
        this.dataTypeConfig = dataTypeConfig;
    }

    // -------------------------------------------------------------------------
    // Manualne pobieranie — dla GUI ("Odśwież teraz")
    // -------------------------------------------------------------------------

    /**
     * Pobiera i zapisuje aktualny pomiar meteo dla stacji.
     * Metoda blokująca — wywołuj poza wątkiem EDT.
     *
     * @param stationId identyfikator stacji IMGW
     * @return pobrany pomiar lub empty gdy stacja nie istnieje w API (404)
     * @throws ApiException         gdy żądanie HTTP się nie powiedzie
     * @throws PersistenceException gdy zapis do repozytorium się nie powiedzie
     */
    public Optional<MeteoData> fetchAndSaveMeteo(String stationId)
            throws ApiException, PersistenceException {
        log.info("Manualne pobieranie meteo: {}", stationId);

        Optional<MeteoData> dataOpt = meteoService.fetchById(stationId);
        if (dataOpt.isEmpty()) {
            log.warn("Brak danych meteo dla stacji {} (404)", stationId);
            return Optional.empty();
        }

        MeteoData data = applyMeteoFilter(dataOpt.get());
        if (data.hasAnyMeasurement()) {
            repository.saveMeteo(data);
            log.debug("Zapisano meteo (manual): {}", data.toDisplayString());
        }

        return Optional.of(data);
    }

    /**
     * Pobiera i zapisuje aktualny pomiar hydro dla stacji.
     * Metoda blokująca — wywołuj poza wątkiem EDT.
     *
     * @param stationId identyfikator stacji IMGW
     * @return pobrany pomiar lub empty gdy stacja nie istnieje w API (404)
     * @throws ApiException         gdy żądanie HTTP się nie powiedzie
     * @throws PersistenceException gdy zapis do repozytorium się nie powiedzie
     */
    public Optional<HydroData> fetchAndSaveHydro(String stationId)
            throws ApiException, PersistenceException {
        log.info("Manualne pobieranie hydro: {}", stationId);

        Optional<HydroData> dataOpt = hydroService.fetchById(stationId);
        if (dataOpt.isEmpty()) {
            log.warn("Brak danych hydro dla stacji {} (404)", stationId);
            return Optional.empty();
        }

        HydroData data = applyHydroFilter(dataOpt.get());
        if (data.hasAnyMeasurement()) {
            repository.saveHydro(data);
            log.debug("Zapisano hydro (manual): {}", data.toDisplayString());
        }

        return Optional.of(data);
    }

    /**
     * Pobiera dane dla wielu stacji naraz — używane przy starcie aplikacji
     * do wstępnego wypełnienia danych lub na żądanie użytkownika "Odśwież wszystkie".
     *
     * Błąd jednej stacji nie przerywa pobierania pozostałych.
     * Błąd sieciowy (isNetworkError) przerywa całą operację.
     *
     * @param stations lista stacji do odświeżenia
     * @return mapa stationId → true (sukces) / false (błąd)
     */
    public Map<String, Boolean> fetchAndSaveAll(List<Station> stations) {
        Map<String, Boolean> results = new HashMap<>();
        if (stations == null || stations.isEmpty()) return results;

        for (Station s : stations) {
            try {
                if (s.getType() == StationType.METEO) {
                    fetchAndSaveMeteo(s.getId());
                } else {
                    fetchAndSaveHydro(s.getId());
                }
                results.put(s.getId(), true);

            } catch (ApiException e) {
                results.put(s.getId(), false);
                if (e.isNetworkError()) {
                    log.error("Błąd sieci — przerywam batch fetch po stacji {}: {}",
                            s.getId(), e.getMessage());
                    break;
                }
                log.warn("Błąd pobierania stacji {}: {}", s.getId(), e.getMessage());

            } catch (PersistenceException e) {
                results.put(s.getId(), false);
                log.error("Błąd zapisu danych stacji {}: {}", s.getId(), e.getMessage());
            }
        }

        long success = results.values().stream().filter(v -> v).count();
        log.info("Batch fetch zakończony: {}/{} stacji OK", success, results.size());
        return results;
    }

    // -------------------------------------------------------------------------
    // Odczyt historii — dla GUI (tabele, wykresy)
    // -------------------------------------------------------------------------

    /**
     * Zwraca najnowszy pomiar meteo dla stacji.
     *
     * @param stationId identyfikator stacji
     * @return najnowszy pomiar lub empty gdy brak danych
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public Optional<MeteoData> getLatestMeteo(String stationId) throws PersistenceException {
        return repository.findLatestMeteo(stationId);
    }

    /**
     * Zwraca najnowszy pomiar hydro dla stacji.
     *
     * @param stationId identyfikator stacji
     * @return najnowszy pomiar lub empty gdy brak danych
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public Optional<HydroData> getLatestHydro(String stationId) throws PersistenceException {
        return repository.findLatestHydro(stationId);
    }

    /**
     * Zwraca historię pomiarów meteo dla stacji z podanego zakresu czasowego.
     *
     * @param stationId identyfikator stacji
     * @param from      początek zakresu (włącznie)
     * @param to        koniec zakresu (włącznie)
     * @return lista pomiarów posortowana rosnąco po czasie; pusta gdy brak danych
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public List<MeteoData> getMeteoHistory(String stationId,
                                           LocalDateTime from,
                                           LocalDateTime to) throws PersistenceException {
        return repository.findMeteoByStationAndRange(stationId, from, to);
    }

    /**
     * Zwraca historię pomiarów hydro dla stacji z podanego zakresu czasowego.
     *
     * @param stationId identyfikator stacji
     * @param from      początek zakresu (włącznie)
     * @param to        koniec zakresu (włącznie)
     * @return lista pomiarów posortowana rosnąco po czasie; pusta gdy brak danych
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public List<HydroData> getHydroHistory(String stationId,
                                           LocalDateTime from,
                                           LocalDateTime to) throws PersistenceException {
        return repository.findHydroByStationAndRange(stationId, from, to);
    }

    /**
     * Zwraca wszystkie pomiary meteo dla stacji (bez filtrowania zakresu).
     * Używać ostrożnie przy dużej ilości danych — preferuj getMeteoHistory z zakresem.
     *
     * @param stationId identyfikator stacji
     * @return lista wszystkich pomiarów, posortowana malejąco po czasie
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public List<MeteoData> getAllMeteo(String stationId) throws PersistenceException {
        return repository.findMeteoByStation(stationId);
    }

    /**
     * Zwraca wszystkie pomiary hydro dla stacji.
     *
     * @param stationId identyfikator stacji
     * @return lista wszystkich pomiarów, posortowana malejąco po czasie
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public List<HydroData> getAllHydro(String stationId) throws PersistenceException {
        return repository.findHydroByStation(stationId);
    }

    // -------------------------------------------------------------------------
    // Czyszczenie historii
    // -------------------------------------------------------------------------

    /**
     * Usuwa dane pomiarowe starsze niż podana liczba dni.
     * Wywołuj np. raz na dobę z harmonogramu lub na żądanie użytkownika z GUI.
     *
     * @param daysToKeep liczba dni historii do zachowania (minimum 1)
     * @throws PersistenceException gdy usunięcie z repozytorium się nie powiedzie
     */
    public void cleanOldData(int daysToKeep) throws PersistenceException {
        int days = Math.max(daysToKeep, 1);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);

        int meteoDeleted = repository.deleteMeteoOlderThan(cutoff);
        int hydroDeleted = repository.deleteHydroOlderThan(cutoff);

        log.info("Wyczyszczono dane starsze niż {} dni: meteo={}, hydro={}",
                days, meteoDeleted, hydroDeleted);
    }

    // -------------------------------------------------------------------------
    // Filtrowanie pól wg DataTypeConfig (DRY z FetchTask)
    // -------------------------------------------------------------------------

    private MeteoData applyMeteoFilter(MeteoData data) {
        if (!dataTypeConfig.isTemperatureEnabled())  data.setTemperature(null);
        if (!dataTypeConfig.isWindEnabled())         data.setWindSpeed(null);
        if (!dataTypeConfig.isPrecipitationEnabled()) data.setPrecipitation(null);
        if (!dataTypeConfig.isPressureEnabled())     data.setPressure(null);
        return data;
    }

    private HydroData applyHydroFilter(HydroData data) {
        if (!dataTypeConfig.isWaterLevelEnabled())       data.setWaterLevel(null);
        if (!dataTypeConfig.isWaterTemperatureEnabled()) data.setWaterTemperature(null);
        return data;
    }
}