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

public class DataCollectionService {

    private static final Logger log = AppLogger.get(DataCollectionService.class);

    private final MeteoApiService  meteoService;
    private final HydroApiService  hydroService;
    private final DataRepository   repository;
    private final DataTypeConfig   dataTypeConfig;

    /**
     * @param meteoService   serwis API meteo (IMGW)
     * @param hydroService   serwis API hydro (IMGW)
     * @param repository     repozytorium do zapisu pobranych pomiarów
     * @param dataTypeConfig konfiguracja zakresu pól (hint dla GUI; nie wpływa
     *                       już na zapis — zob. {@link #fetchAndSaveMeteo})
     */
    public DataCollectionService(MeteoApiService meteoService,
                                 HydroApiService hydroService,
                                 DataRepository repository,
                                 DataTypeConfig dataTypeConfig) {
        this.meteoService   = meteoService;
        this.hydroService   = hydroService;
        this.repository     = repository;
        this.dataTypeConfig = dataTypeConfig;
    }

    /**
     * Zwraca konfigurację zakresu zbieranych danych skojarzoną z tym serwisem.
     *
     * Uwaga: jest to snapshot wzięty przy konstrukcji serwisu. Komponenty GUI
     * potrzebujące „żywego" odczytu po zmianie ustawień (np. DataViewPanel)
     * powinny czytać bezpośrednio z {@code AppConfig.getDataTypeConfig()}.
     *
     * @return konfiguracja zakresu danych aktywna w tej sesji
     */
    public DataTypeConfig getDataTypeConfig() {
        return dataTypeConfig;
    }

    /**
     * Sprawdza czy stacja o podanym ID rzeczywiście istnieje w API IMGW,
     * bez zapisywania jakichkolwiek danych do repozytorium. Używane przez
     * StationManagerPanel przed dodaniem nowej stacji — zapobiega dodaniu
     * nieistniejącego ID, które nigdy nie zwróciłoby żadnych danych.
     *
     * Zwykłe 404 (stacja nie znaleziona) jest tu zwracane jako {@code false},
     * nie jako wyjątek — {@link ApiException} jest rzucany tylko dla rzeczywistych
     * błędów komunikacji (timeout, brak sieci, błąd serwera).
     *
     * @param stationId identyfikator stacji do zweryfikowania
     * @param type      typ stacji — decyduje, który serwis API odpytać
     * @return true jeśli stacja istnieje i API zwróciło dla niej dane
     * @throws ApiException gdy żądanie HTTP nie powiedzie się z innego powodu niż 404
     */
    public boolean stationExists(String stationId, StationType type) throws ApiException {
        boolean exists = (type == StationType.METEO)
                ? meteoService.fetchById(stationId).isPresent()
                : hydroService.fetchById(stationId).isPresent();

        log.debug("Weryfikacja istnienia stacji {} [{}]: {}", stationId, type, exists);
        return exists;
    }

    /**
     * Pobiera i zapisuje aktualny pomiar meteo dla stacji. Metoda blokująca —
     * wywołuj poza wątkiem EDT (zawsze przez SwingWorker).
     *
     * Wszystkie pola dostarczone przez API są zapisywane bez filtrowania.
     * Wybór, które kolumny pokazywać w GUI, odbywa się dopiero w warstwie
     * widoku (DataViewPanel czyta DataTypeConfig na bieżąco). Dzięki temu
     * odznaczenie pola w Ustawieniach nie powoduje utraty danych historycznych.
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

        // Bug fix: NIE filtrujemy pól przed zapisem na podstawie DataTypeConfig.
        // Wcześniejsza wersja zerowała np. temperature gdy checkbox był odznaczony —
        // skutkowało to tym, że po późniejszym ponownym włączeniu checkboxa
        // historyczne dane w tabeli pokazywały „—" zamiast realnej wartości,
        // która była dostępna w API.
        // Zapis zawsze trzyma wszystkie pola; filtrowanie odbywa się dopiero
        // w warstwie GUI (DataViewPanel), która dynamicznie pokazuje/ukrywa
        // kolumny na podstawie aktualnego DataTypeConfig.
        MeteoData data = dataOpt.get();
        if (data.hasAnyMeasurement()) {
            repository.saveMeteo(data);
            log.debug("Zapisano meteo (manual): {}", data.toDisplayString());
        }

        return Optional.of(data);
    }

    /**
     * Pobiera i zapisuje aktualny pomiar hydro dla stacji. Metoda blokująca —
     * wywołuj poza wątkiem EDT.
     *
     * Wszystkie pola dostarczone przez API są zapisywane bez filtrowania
     * (zob. {@link #fetchAndSaveMeteo} po szczegółowy opis polityki zapisu).
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

        // Bug fix: bez filtrowania (patrz komentarz w fetchAndSaveMeteo).
        HydroData data = dataOpt.get();
        if (data.hasAnyMeasurement()) {
            repository.saveHydro(data);
            log.debug("Zapisano hydro (manual): {}", data.toDisplayString());
        }

        return Optional.of(data);
    }

    /**
     * Pobiera dane dla wielu stacji naraz — używane przy starcie aplikacji
     * do wstępnego wypełnienia danych lub na żądanie użytkownika
     * („Odśwież wszystkie").
     *
     * Błąd jednej stacji NIE przerywa pobierania pozostałych — wyjątek jest
     * łapany, a wynik dla tej stacji oznaczany jako false w zwracanej mapie.
     * Wyjątkiem jest błąd sieciowy ({@link ApiException#isNetworkError()}) —
     * wtedy cała operacja jest przerywana, bo dalsze próby i tak by się
     * nie powiodły.
     *
     * @param stations lista stacji do odświeżenia (null lub pusta = brak operacji)
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

    /**
     * Zwraca najnowszy pomiar meteo dla stacji (po znaczniku czasu).
     *
     * @param stationId identyfikator stacji
     * @return najnowszy pomiar lub empty gdy brak danych
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public Optional<MeteoData> getLatestMeteo(String stationId) throws PersistenceException {
        return repository.findLatestMeteo(stationId);
    }

    /**
     * Zwraca najnowszy pomiar hydro dla stacji (po znaczniku czasu).
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
     * Używać ostrożnie przy dużej ilości danych — preferuj
     * {@link #getMeteoHistory(String, LocalDateTime, LocalDateTime)} z zakresem.
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

    /**
     * Usuwa dane pomiarowe starsze niż podana liczba dni. Wywoływać np. raz
     * na dobę z harmonogramu lub na żądanie użytkownika z SettingsPanel.
     * Wartości mniejsze niż 1 są przycinane do 1.
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

    /**
     * Usuwa wszystkie zapisane dane pomiarowe i ostrzeżenia. Lista stacji
     * (stations.json / tabela stations) oraz ustawienia aplikacji pozostają
     * nietknięte. Operacji nie można cofnąć — wywołujący jest odpowiedzialny
     * za uzyskanie potwierdzenia od użytkownika.
     *
     * @throws PersistenceException gdy operacja na repozytorium się nie powiedzie
     */
    public void clearAllData() throws PersistenceException {
        repository.clearAllData();
        log.info("Wszystkie dane pomiarowe i ostrzeżenia wyczyszczone");
    }
}