package ecoalerter.persistence;

import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Interfejs repozytorium danych aplikacji EcoAlerter.
 *
 * Definiuje kontrakt dla wszystkich operacji odczytu i zapisu, niezależnie
 * od wybranego trybu persystencji. Implementowany przez DatabaseRepository
 * (tryb DATABASE) oraz FileRepository (tryb FILE).
 *
 * Wyjątek PersistenceException jest rzucany przy każdym błędzie I/O lub SQL,
 * co pozwala warstwie serwisowej obsługiwać błędy jednolicie bez znajomości
 * szczegółów implementacji.
*/
public interface DataRepository {

    /**
     * Zapisuje stację lub aktualizuje ją jeśli już istnieje (upsert).
     *
     * @param station stacja do zapisania; nie może być null
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveStation(Station station) throws PersistenceException;

    /**
     * Usuwa stację oraz wszystkie powiązane z nią pomiary i ostrzeżenia.
     *
     * @param stationId identyfikator stacji do usunięcia
     * @param type      typ stacji (wymagany bo id+type = klucz główny)
     * @throws PersistenceException gdy usunięcie się nie powiedzie
    */
    void deleteStation(String stationId, StationType type) throws PersistenceException;

    /**
     * Zwraca wszystkie zapisane stacje.
     *
     * @return lista stacji; pusta gdy brak zapisanych stacji
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<Station> findAllStations() throws PersistenceException;

    /**
     * Zwraca tylko aktywne stacje danego typu.
     *
     * @param type typ stacji (METEO lub HYDRO)
     * @return lista aktywnych stacji podanego typu
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<Station> findActiveStations(StationType type) throws PersistenceException;

    /**
     * Zapisuje pojedynczy pomiar meteorologiczny.
     * Jeśli pomiar dla tej stacji i tego czasu już istnieje, jest ignorowany (no-op).
     *
     * @param data pomiar do zapisania; nie może być null
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveMeteo(MeteoData data) throws PersistenceException;

    /**
     * Zapisuje listę pomiarów meteorologicznych w jednej transakcji (batch).
     * Pomiary już istniejące są pomijane.
     *
     * @param dataList lista pomiarów; pusta lista jest dozwolona (no-op)
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveAllMeteo(List<MeteoData> dataList) throws PersistenceException;

    /**
     * Zwraca wszystkie pomiary meteo dla podanej stacji, posortowane malejąco po czasie.
     *
     * @param stationId identyfikator stacji
     * @return lista pomiarów; pusta gdy brak danych
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<MeteoData> findMeteoByStation(String stationId) throws PersistenceException;

    /**
     * Zwraca pomiary meteo dla stacji z podanego zakresu czasowego.
     *
     * @param stationId identyfikator stacji
     * @param from      początek zakresu (włącznie)
     * @param to        koniec zakresu (włącznie)
     * @return lista pomiarów z zakresu, posortowana rosnąco po czasie
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<MeteoData> findMeteoByStationAndRange(String stationId,
                                               LocalDateTime from,
                                               LocalDateTime to) throws PersistenceException;

    /**
     * Zwraca najnowszy pomiar meteo dla podanej stacji.
     *
     * @param stationId identyfikator stacji
     * @return Optional z najnowszym pomiarem lub empty gdy brak danych
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    Optional<MeteoData> findLatestMeteo(String stationId) throws PersistenceException;

    /**
     * Usuwa pomiary meteo starsze niż podana data.
     * Używany przez mechanizm automatycznego czyszczenia danych historycznych.
     *
     * @param olderThan pomiary starsze niż ta data zostaną usunięte
     * @return liczba usuniętych rekordów
     * @throws PersistenceException gdy usunięcie się nie powiedzie
     */
    int deleteMeteoOlderThan(LocalDateTime olderThan) throws PersistenceException;

    /**
     * Zapisuje pojedynczy pomiar hydrologiczny.
     * Jeśli pomiar dla tej stacji i tego czasu już istnieje, jest ignorowany.
     *
     * @param data pomiar do zapisania; nie może być null
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveHydro(HydroData data) throws PersistenceException;

    /**
     * Zapisuje listę pomiarów hydrologicznych w jednej transakcji (batch).
     *
     * @param dataList lista pomiarów; pusta lista jest dozwolona (no-op)
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveAllHydro(List<HydroData> dataList) throws PersistenceException;

    /**
     * Zwraca wszystkie pomiary hydro dla podanej stacji, posortowane malejąco po czasie.
     *
     * @param stationId identyfikator stacji
     * @return lista pomiarów; pusta gdy brak danych
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<HydroData> findHydroByStation(String stationId) throws PersistenceException;

    /**
     * Zwraca pomiary hydro dla stacji z podanego zakresu czasowego.
     *
     * @param stationId identyfikator stacji
     * @param from      początek zakresu (włącznie)
     * @param to        koniec zakresu (włącznie)
     * @return lista pomiarów z zakresu, posortowana rosnąco po czasie
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<HydroData> findHydroByStationAndRange(String stationId,
                                               LocalDateTime from,
                                               LocalDateTime to) throws PersistenceException;

    /**
     * Zwraca najnowszy pomiar hydro dla podanej stacji.
     *
     * @param stationId identyfikator stacji
     * @return Optional z najnowszym pomiarem lub empty gdy brak danych
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    Optional<HydroData> findLatestHydro(String stationId) throws PersistenceException;

    /**
     * Usuwa pomiary hydro starsze niż podana data.
     *
     * @param olderThan pomiary starsze niż ta data zostaną usunięte
     * @return liczba usuniętych rekordów
     * @throws PersistenceException gdy usunięcie się nie powiedzie
    */
    int deleteHydroOlderThan(LocalDateTime olderThan) throws PersistenceException;

    /**
     * Zapisuje ostrzeżenie lub aktualizuje je jeśli już istnieje (upsert po id).
     *
     * @param warning ostrzeżenie do zapisania; nie może być null
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveWarning(Warning warning) throws PersistenceException;

    /**
     * Zapisuje listę ostrzeżeń w jednej operacji.
     *
     * @param warnings lista ostrzeżeń; pusta lista jest dozwolona (no-op)
     * @throws PersistenceException gdy zapis się nie powiedzie
    */
    void saveAllWarnings(List<Warning> warnings) throws PersistenceException;

    /**
     * Zwraca wszystkie aktywne ostrzeżenia (takie których valid_until jest w przyszłości
     * lub nie jest ustawione).
     *
     * @return lista aktywnych ostrzeżeń, posortowana malejąco po poziomie
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<Warning> findActiveWarnings() throws PersistenceException;

    /**
     * Zwraca aktywne ostrzeżenia o poziomie co najmniej minLevel.
     *
     * @param minLevel minimalny poziom alertu do zwrócenia
     * @return przefiltrowana lista aktywnych ostrzeżeń
     * @throws PersistenceException gdy odczyt się nie powiedzie
    */
    List<Warning> findActiveWarningsByMinLevel(WarningLevel minLevel) throws PersistenceException;

    /**
     * Usuwa ostrzeżenia, których data ważności minęła.
     *
     * @return liczba usuniętych rekordów
     * @throws PersistenceException gdy usunięcie się nie powiedzie
    */
    int deleteExpiredWarnings() throws PersistenceException;

    /**
     * Zamyka zasoby repozytorium (połączenia z bazą, uchwyty plików).
     * Wywoływać w shutdown hooku aplikacji.
    */
    void close();
}