package ecoalerter.service;

import ecoalerter.api.ApiException;
import ecoalerter.api.WarningApiService;
import ecoalerter.config.AppConfig;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Serwis zarządzający cyklem życia ostrzeżeń meteorologicznych i hydrologicznych.
 *
 * Pobiera ostrzeżenia z API IMGW, stosuje filtr poziomu z konfiguracji,
 * zapisuje do repozytorium i udostępnia metody odczytu dla GUI.
 *
 * Dostarcza też WarningSummary — zagregowane informacje o aktywnych
 * alertach do wyświetlenia w StatusBar i AlertBadge.
 */
public class WarningService {

    private static final Logger log = AppLogger.get(WarningService.class);

    private final WarningApiService warningApiService;
    private final DataRepository    repository;
    private final AppConfig         config;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public WarningService(WarningApiService warningApiService,
                          DataRepository repository,
                          AppConfig config) {
        this.warningApiService = warningApiService;
        this.repository        = repository;
        this.config            = config;
    }

    // -------------------------------------------------------------------------
    // Pobieranie i zapis
    // -------------------------------------------------------------------------

    /**
     * Pobiera aktualne ostrzeżenia z API IMGW, filtruje wg minimalnego poziomu
     * z konfiguracji i zapisuje do repozytorium.
     *
     * Zastępuje poprzedni zestaw ostrzeżeń (w trybie FILE) lub robi upsert
     * (w trybie DATABASE). Wygasłe ostrzeżenia są usuwane.
     *
     * @return lista aktualnych, przefiltrowanych ostrzeżeń
     * @throws ApiException         gdy żądanie do API IMGW się nie powiedzie
     * @throws PersistenceException gdy zapis do repozytorium się nie powiedzie
     */
    public List<Warning> fetchAndSave() throws ApiException, PersistenceException {
        log.info("Pobieranie ostrzeżeń IMGW...");

        List<Warning> all      = warningApiService.fetchAllWarnings();
        WarningLevel  minLevel = config.getWarningsFilterLevel();
        List<Warning> filtered = warningApiService.filterByMinLevel(all, minLevel);

        for (Warning w : filtered) {
            AppLogger.logWarningDetected(
                    w.getLevel().name(), w.getType().name(), w.getMessage());
        }

        repository.saveAllWarnings(filtered);
        int cleaned = repository.deleteExpiredWarnings();

        log.info("Ostrzeżenia zaktualizowane: łącznie={}, po filtrze={}, usunięto wygasłych={}",
                all.size(), filtered.size(), cleaned);

        return filtered;
    }

    // -------------------------------------------------------------------------
    // Odczyt dla GUI
    // -------------------------------------------------------------------------

    /**
     * Zwraca wszystkie aktualnie aktywne ostrzeżenia z repozytorium.
     *
     * @return lista aktywnych ostrzeżeń posortowana malejąco po poziomie
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public List<Warning> getActiveWarnings() throws PersistenceException {
        return repository.findActiveWarnings();
    }

    /**
     * Zwraca aktywne ostrzeżenia o poziomie co najmniej minLevel.
     *
     * @param minLevel minimalny poziom alertu
     * @return przefiltrowana lista ostrzeżeń
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public List<Warning> getActiveWarningsByLevel(WarningLevel minLevel)
            throws PersistenceException {
        return repository.findActiveWarningsByMinLevel(minLevel);
    }

    /**
     * Zwraca aktywne ostrzeżenia powiązane z konkretną stacją
     * lub regionalne (stationId == null).
     *
     * @param stationId identyfikator stacji
     * @return ostrzeżenia dotyczące tej stacji lub ogólne
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public List<Warning> getActiveWarningsForStation(String stationId)
            throws PersistenceException {
        List<Warning> all = repository.findActiveWarnings();
        return warningApiService.filterByStation(all, stationId);
    }

    /**
     * Zwraca aktywne ostrzeżenia danego typu.
     *
     * @param type METEO lub HYDRO
     * @return lista ostrzeżeń danego typu
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public List<Warning> getActiveWarningsByType(StationType type)
            throws PersistenceException {
        List<Warning> all = repository.findActiveWarnings();
        return warningApiService.filterByType(all, type);
    }

    // -------------------------------------------------------------------------
    // Stany alarmowe — dla StatusBar i AlertBadge
    // -------------------------------------------------------------------------

    /**
     * Sprawdza czy istnieje co najmniej jedno aktywne ostrzeżenie 3. stopnia (RED).
     * Używane przez AlertBadge do migania.
     *
     * @return true jeśli jest przynajmniej jedno aktywne ostrzeżenie RED
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public boolean hasActiveRedWarnings() throws PersistenceException {
        return !repository.findActiveWarningsByMinLevel(WarningLevel.RED).isEmpty();
    }

    /**
     * Sprawdza czy istnieje jakiekolwiek aktywne ostrzeżenie (dowolnego poziomu).
     *
     * @return true jeśli są aktywne ostrzeżenia
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public boolean hasAnyActiveWarning() throws PersistenceException {
        return !repository.findActiveWarnings().isEmpty();
    }

    /**
     * Zwraca najwyższy poziom spośród aktywnych ostrzeżeń.
     * Przydatne do ustawienia koloru AlertBadge bez pobierania pełnej listy.
     *
     * @return najwyższy aktywny poziom lub null gdy brak ostrzeżeń
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public WarningLevel getHighestActiveLevel() throws PersistenceException {
        List<Warning> active = repository.findActiveWarnings();
        if (active.isEmpty()) return null;
        return active.stream()
                .map(Warning::getLevel)
                .filter(l -> l != null)
                .max((a, b) -> Integer.compare(a.ordinal(), b.ordinal()))
                .orElse(null);
    }

    /**
     * Agreguje aktywne ostrzeżenia do podsumowania wg poziomu i typu.
     * Używane przez StatusBar do wyświetlenia zwięzłej informacji.
     *
     * @return obiekt WarningSummary z liczbami ostrzeżeń
     * @throws PersistenceException gdy odczyt się nie powiedzie
     */
    public WarningSummary getSummary() throws PersistenceException {
        List<Warning> active = repository.findActiveWarnings();
        return WarningSummary.from(active);
    }

    // -------------------------------------------------------------------------
    // Czyszczenie
    // -------------------------------------------------------------------------

    /**
     * Usuwa z repozytorium ostrzeżenia, których data ważności minęła.
     *
     * @return liczba usuniętych ostrzeżeń
     * @throws PersistenceException gdy usunięcie się nie powiedzie
     */
    public int cleanExpiredWarnings() throws PersistenceException {
        int deleted = repository.deleteExpiredWarnings();
        if (deleted > 0) log.info("Usunięto {} wygasłych ostrzeżeń", deleted);
        return deleted;
    }

    // =========================================================================
    // WarningSummary — zagregowane dane dla GUI
    // =========================================================================

    /**
     * Podsumowanie aktywnych ostrzeżeń agregowane wg poziomu i typu.
     * Tworzony przez WarningService.getSummary() i przekazywany do StatusBar.
     */
    public static class WarningSummary {

        private final int total;
        private final Map<WarningLevel, Integer> byLevel;
        private final Map<StationType, Integer>  byType;

        private WarningSummary(int total,
                               Map<WarningLevel, Integer> byLevel,
                               Map<StationType, Integer> byType) {
            this.total   = total;
            this.byLevel = Collections.unmodifiableMap(byLevel);
            this.byType  = Collections.unmodifiableMap(byType);
        }

        /**
         * Buduje WarningSummary z listy ostrzeżeń.
         *
         * @param warnings lista aktywnych ostrzeżeń
         * @return zagregowane podsumowanie
         */
        public static WarningSummary from(List<Warning> warnings) {
            Map<WarningLevel, Integer> byLevel = new EnumMap<>(WarningLevel.class);
            Map<StationType, Integer>  byType  = new EnumMap<>(StationType.class);

            for (Warning w : warnings) {
                if (w.getLevel() != null) {
                    byLevel.merge(w.getLevel(), 1, Integer::sum);
                }
                if (w.getType() != null) {
                    byType.merge(w.getType(), 1, Integer::sum);
                }
            }

            return new WarningSummary(warnings.size(), byLevel, byType);
        }

        /** Łączna liczba aktywnych ostrzeżeń. */
        public int getTotal() { return total; }

        /** Czy w ogóle są jakieś aktywne ostrzeżenia. */
        public boolean isEmpty() { return total == 0; }

        /** Liczba ostrzeżeń danego poziomu (0 jeśli brak). */
        public int getCount(WarningLevel level) {
            return byLevel.getOrDefault(level, 0);
        }

        /** Liczba ostrzeżeń danego typu (0 jeśli brak). */
        public int getCount(StationType type) {
            return byType.getOrDefault(type, 0);
        }

        /**
         * Najwyższy aktywny poziom ostrzeżenia lub null gdy brak ostrzeżeń.
         * Używane przez AlertBadge do doboru koloru.
         */
        public WarningLevel getHighestLevel() {
            return byLevel.keySet().stream()
                    .max((a, b) -> Integer.compare(a.ordinal(), b.ordinal()))
                    .orElse(null);
        }

        @Override
        public String toString() {
            return String.format("WarningSummary{total=%d, RED=%d, ORANGE=%d, YELLOW=%d}",
                    total,
                    getCount(WarningLevel.RED),
                    getCount(WarningLevel.ORANGE),
                    getCount(WarningLevel.YELLOW));
        }
    }
}