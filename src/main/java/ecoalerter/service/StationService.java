package ecoalerter.service;

import ecoalerter.config.AppConfig;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.scheduler.TaskSchedulerManager;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Serwis zarządzający stacjami pomiarowymi.
 *
 * Koordynuje zapis stacji do repozytorium oraz planowanie zadań w schedulerze.
 * Każda operacja modyfikująca stację (dodanie, usunięcie, zmiana aktywności)
 * jest natychmiast odzwierciedlana w harmonogramie bez restartu aplikacji.
 *
 * Wszystkie metody mutujące zapisują zmiany do repozytorium PRZED aktualizacją
 * schedulera — gwarantuje to spójność przy ewentualnym błędzie schedulera.
 */
public class StationService {

    private static final Logger log = AppLogger.get(StationService.class);

    private final DataRepository       repository;
    private final TaskSchedulerManager scheduler;
    private final AppConfig            config;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public StationService(DataRepository repository,
                          TaskSchedulerManager scheduler,
                          AppConfig config) {
        this.repository = repository;
        this.scheduler  = scheduler;
        this.config     = config;
    }

    // -------------------------------------------------------------------------
    // Dodawanie i usuwanie
    // -------------------------------------------------------------------------

    /**
     * Dodaje nową stację do systemu i planuje dla niej zadanie cykliczne.
     * Jeśli stacja o tym samym id i type już istnieje, aktualizuje jej dane (upsert).
     *
     * @param station stacja do dodania; nie może być null
     * @throws PersistenceException gdy zapis do repozytorium się nie powiedzie
     * @throws IllegalArgumentException gdy station lub station.getId() jest null
     */
    public void addStation(Station station) throws PersistenceException {
        if (station == null || station.getId() == null) {
            throw new IllegalArgumentException("Stacja i jej ID nie mogą być null");
        }

        repository.saveStation(station);
        AppLogger.logStationChange("DODANO", station.getId(), station.getName(),
                station.getType().name());

        if (station.isActive()) {
            scheduler.scheduleStation(station);
        }

        log.info("Dodano stację: {} [{}]", station.getDisplayLabel(), station.getId());
    }

    /**
     * Usuwa stację z systemu, anuluje jej zadanie i usuwa powiązane dane.
     *
     * @param stationId identyfikator stacji
     * @param type      typ stacji (METEO lub HYDRO)
     * @throws PersistenceException gdy usunięcie z repozytorium się nie powiedzie
     */
    public void removeStation(String stationId, StationType type) throws PersistenceException {
        scheduler.unscheduleStation(stationId);
        repository.deleteStation(stationId, type);
        AppLogger.logStationChange("USUNIETO", stationId, "", type.name());
        log.info("Usunięto stację {} [{}]", stationId, type);
    }

    // -------------------------------------------------------------------------
    // Aktywacja i dezaktywacja
    // -------------------------------------------------------------------------

    /**
     * Aktywuje stację — wznawia cykliczne pobieranie danych.
     * Jeśli stacja nie istnieje w repozytorium, operacja jest ignorowana z ostrzeżeniem.
     *
     * @param stationId identyfikator stacji
     * @param type      typ stacji
     * @throws PersistenceException gdy aktualizacja repozytorium się nie powiedzie
     */
    public void activateStation(String stationId, StationType type) throws PersistenceException {
        Optional<Station> opt = findStation(stationId, type);
        if (opt.isEmpty()) {
            log.warn("Nie można aktywować — stacja nie istnieje: {} [{}]", stationId, type);
            return;
        }

        Station station = opt.get();
        station.setActive(true);
        repository.saveStation(station);
        scheduler.scheduleStation(station);

        AppLogger.logConfigChange("station.active." + stationId, "false", "true");
        log.info("Aktywowano stację: {} [{}]", stationId, type);
    }

    /**
     * Dezaktywuje stację — wstrzymuje pobieranie danych bez usuwania historii.
     *
     * @param stationId identyfikator stacji
     * @param type      typ stacji
     * @throws PersistenceException gdy aktualizacja repozytorium się nie powiedzie
     */
    public void deactivateStation(String stationId, StationType type) throws PersistenceException {
        Optional<Station> opt = findStation(stationId, type);
        if (opt.isEmpty()) {
            log.warn("Nie można dezaktywować — stacja nie istnieje: {} [{}]", stationId, type);
            return;
        }

        Station station = opt.get();
        station.setActive(false);
        repository.saveStation(station);
        scheduler.unscheduleStation(stationId);

        AppLogger.logConfigChange("station.active." + stationId, "true", "false");
        log.info("Dezaktywowano stację: {} [{}]", stationId, type);
    }

    // -------------------------------------------------------------------------
    // Zmiana interwału
    // -------------------------------------------------------------------------

    /**
     * Zmienia interwał odpytywania API dla stacji.
     * Zmiana jest natychmiast stosowana — scheduler przeplanowuje zadanie.
     *
     * @param stationId       identyfikator stacji
     * @param type            typ stacji
     * @param intervalSeconds nowy interwał w sekundach (minimum 60)
     * @throws PersistenceException gdy aktualizacja repozytorium się nie powiedzie
     */
    public void updateInterval(String stationId, StationType type, int intervalSeconds)
            throws PersistenceException {
        Optional<Station> opt = findStation(stationId, type);
        if (opt.isEmpty()) {
            log.warn("Nie można zmienić interwału — stacja nie istnieje: {} [{}]", stationId, type);
            return;
        }

        Station station = opt.get();
        int clamped = Math.max(intervalSeconds, 60);
        station.setIntervalSeconds(clamped);
        repository.saveStation(station);

        if (station.isActive()) {
            scheduler.rescheduleStation(station, clamped);
        }

        log.info("Zmieniono interwał stacji {} [{}] na {} s", stationId, type, clamped);
    }

    // -------------------------------------------------------------------------
    // Odczyt
    // -------------------------------------------------------------------------

    /**
     * Zwraca listę wszystkich zarejestrowanych stacji.
     *
     * @return lista stacji; pusta gdy brak zapisanych stacji
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public List<Station> getAllStations() throws PersistenceException {
        return repository.findAllStations();
    }

    /**
     * Zwraca aktywne stacje podanego typu.
     *
     * @param type typ stacji (METEO lub HYDRO)
     * @return lista aktywnych stacji
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public List<Station> getActiveByType(StationType type) throws PersistenceException {
        return repository.findActiveStations(type);
    }

    /**
     * Sprawdza czy stacja o podanym id i typie istnieje w repozytorium.
     *
     * @param stationId identyfikator stacji
     * @param type      typ stacji
     * @return true jeśli stacja istnieje
     * @throws PersistenceException gdy odczyt z repozytorium się nie powiedzie
     */
    public boolean exists(String stationId, StationType type) throws PersistenceException {
        return findStation(stationId, type).isPresent();
    }

    /**
     * Przy starcie aplikacji planuje wszystkie aktywne stacje z repozytorium.
     * Wywoływać raz po inicjalizacji schedulera.
     *
     * @throws PersistenceException gdy odczyt stacji z repozytorium się nie powiedzie
     */
    public void scheduleAllActive() throws PersistenceException {
        List<Station> all = repository.findAllStations();
        scheduler.startAll(all);
        log.info("Zaplanowano {} aktywnych stacji przy starcie", all.stream()
                .filter(Station::isActive).count());
    }

    // -------------------------------------------------------------------------
    // Pomocnicze
    // -------------------------------------------------------------------------

    private Optional<Station> findStation(String stationId, StationType type)
            throws PersistenceException {
        return repository.findAllStations().stream()
                .filter(s -> s.getId().equals(stationId) && s.getType() == type)
                .findFirst();
    }
}