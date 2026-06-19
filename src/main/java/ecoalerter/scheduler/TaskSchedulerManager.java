package ecoalerter.scheduler;

import ecoalerter.api.HydroApiService;
import ecoalerter.api.MeteoApiService;
import ecoalerter.api.WarningApiService;
import ecoalerter.config.AppConfig;
import ecoalerter.config.DataTypeConfig;
import ecoalerter.model.Station;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.persistence.DataRepository;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralny zarządca harmonogramu zadań cyklicznych.
 *
 * Odpowiada za:
 * - planowanie i anulowanie zadań FetchTask dla każdej aktywnej stacji,
 * - cykliczne pobieranie ostrzeżeń IMGW (niezależne od interwałów stacji),
 * - dynamiczne dodawanie, usuwanie i zmianę interwałów bez restartu,
 * - bezpieczne zamknięcie wszystkich wątków przy zamykaniu aplikacji.
 *
 * Każda stacja ma własne zadanie w puli wątków. Mapa tasks jest ConcurrentHashMap
 * — bezpieczna do równoczesnego dostępu z wątku GUI i wątków schedulera.
 *
 * Przykład użycia:
 *
 *   TaskSchedulerManager scheduler = new TaskSchedulerManager(config, ...);
 *   scheduler.startAll(repo.findAllStations());
 *   // po zamknięciu aplikacji:
 *   scheduler.shutdown();
 */
public class TaskSchedulerManager {

    private static final Logger log = AppLogger.get(TaskSchedulerManager.class);

    private final AppConfig            config;
    private final ScheduleConfig       scheduleConfig;
    private final MeteoApiService      meteoService;
    private final HydroApiService      hydroService;
    private final WarningApiService    warningService;
    private final DataRepository       repository;
    private final DataTypeConfig       dataTypeConfig;

    private final ScheduledExecutorService executor;

    // stationId -> aktywne zadanie
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    // aktywne zadanie ostrzeżeń
    private ScheduledFuture<?> warningTask;

    // zewnętrzni odbiorcy zdarzeń (GUI, statystyki)
    private final List<FetchTask.FetchListener>   fetchListeners   = new ArrayList<>();
    private final List<WarningListener>            warningListeners = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Interfejs wynikowy dla ostrzeżeń
    // -------------------------------------------------------------------------

    /**
     * Odbiornik nowych ostrzeżeń IMGW. Implementowany przez NotificationService / GUI.
     */
    public interface WarningListener {

        /**
         * Wywoływany po pobraniu nowej listy aktywnych ostrzeżeń.
         *
         * @param warnings lista aktywnych ostrzeżeń spełniających filtr poziomu
         */
        void onWarningsRefreshed(List<Warning> warnings);
    }

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    /**
     * @param config         konfiguracja aplikacji (interwały, tryb danych)
     * @param scheduleConfig konfiguracja niestandardowych interwałów per stacja
     * @param meteoService   serwis API meteo
     * @param hydroService   serwis API hydro
     * @param warningService serwis API ostrzeżeń
     * @param repository     repozytorium do zapisu danych
     */
    public TaskSchedulerManager(AppConfig config,
                                ScheduleConfig scheduleConfig,
                                MeteoApiService meteoService,
                                HydroApiService hydroService,
                                WarningApiService warningService,
                                DataRepository repository) {
        this.config         = config;
        this.scheduleConfig = scheduleConfig;
        this.meteoService   = meteoService;
        this.hydroService   = hydroService;
        this.warningService = warningService;
        this.repository     = repository;
        this.dataTypeConfig = config.getDataTypeConfig();
        this.executor       = buildExecutor(config.getRaw("scheduler.thread.pool.size"));

        log.info("TaskSchedulerManager zainicjalizowany [wątki={}]",
                config.getRaw("scheduler.thread.pool.size"));
    }

    /**
     * Konstruktor package-private do użycia w testach jednostkowych.
     * Przyjmuje gotowy executor zamiast budować własny, co pozwala
     * mockować harmonogram bez uruchamiania prawdziwych wątków.
     *
     * @param executor wstrzykiwany executor (mock lub SynchronousExecutor)
     */
    TaskSchedulerManager(AppConfig config,
                         ScheduleConfig scheduleConfig,
                         MeteoApiService meteoService,
                         HydroApiService hydroService,
                         WarningApiService warningService,
                         DataRepository repository,
                         ScheduledExecutorService executor) {
        this.config         = config;
        this.scheduleConfig = scheduleConfig;
        this.meteoService   = meteoService;
        this.hydroService   = hydroService;
        this.warningService = warningService;
        this.repository     = repository;
        this.dataTypeConfig = config.getDataTypeConfig();
        this.executor       = executor;
    }

    // -------------------------------------------------------------------------
    // Zarządzanie stacjami
    // -------------------------------------------------------------------------

    /**
     * Planuje zadanie cykliczne dla podanej stacji.
     * Jeśli zadanie dla tej stacji już istnieje, zostaje najpierw anulowane.
     *
     * @param station stacja do zaplanowania; musi być aktywna
     */
    public void scheduleStation(Station station) {
        if (!station.isActive()) {
            log.debug("Stacja {} jest nieaktywna — nie planuję zadania", station.getId());
            return;
        }

        unscheduleStation(station.getId()); // anuluj poprzednie jeśli istnieje

        int intervalSeconds = scheduleConfig.getInterval(
                station.getId(),
                config.getSchedulerDefaultIntervalSeconds()
        );

        FetchTask task = new FetchTask(
                station, meteoService, hydroService, repository, dataTypeConfig);

        // przekazanie globalnych listenerów do zadania
        fetchListeners.forEach(task::addListener);

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                task,
                0,                   // pierwsze uruchomienie natychmiast
                intervalSeconds,
                TimeUnit.SECONDS
        );

        tasks.put(station.getId(), future);
        log.info("Zaplanowano stację {} [{}] co {} s",
                station.getId(), station.getType(), intervalSeconds);
    }

    /**
     * Anuluje zadanie dla podanej stacji. Bezpieczne gdy stacja nie była zaplanowana.
     *
     * @param stationId identyfikator stacji do anulowania
     */
    public void unscheduleStation(String stationId) {
        ScheduledFuture<?> existing = tasks.remove(stationId);
        if (existing != null) {
            existing.cancel(false); // nie przerywaj trwającego cyklu
            log.info("Anulowano zadanie dla stacji {}", stationId);
        }
    }

    /**
     * Zmienia interwał istniejącej stacji.
     * Anuluje stare zadanie i tworzy nowe z nowym interwałem.
     *
     * @param station         stacja do przeplanowania
     * @param newIntervalSecs nowy interwał w sekundach
     */
    public void rescheduleStation(Station station, int newIntervalSecs) {
        scheduleConfig.setInterval(station.getId(), newIntervalSecs);
        scheduleStation(station);
        log.info("Przeplanowano stację {} — nowy interwał: {} s",
                station.getId(), newIntervalSecs);
        AppLogger.logConfigChange(
                "interval." + station.getId(),
                "poprzedni",
                newIntervalSecs + "s");
    }

    /**
     * Planuje wszystkie aktywne stacje z listy. Stacje nieaktywne są pomijane.
     *
     * @param stations lista stacji do zaplanowania
     */
    public void startAll(List<Station> stations) {
        if (stations == null || stations.isEmpty()) {
            log.info("Brak stacji do zaplanowania");
            return;
        }

        int scheduled = 0;
        for (Station s : stations) {
            if (s.isActive()) {
                scheduleStation(s);
                scheduled++;
            }
        }
        log.info("Zaplanowano {}/{} stacji", scheduled, stations.size());
    }

    /**
     * Uruchamia lub zatrzymuje zadanie stacji w zależności od jej aktywności.
     * Wywoływane przez GUI gdy użytkownik zmienia status stacji.
     *
     * @param station zaktualizowana stacja
     */
    public void onStationUpdated(Station station) {
        if (station.isActive()) {
            scheduleStation(station);
        } else {
            unscheduleStation(station.getId());
        }
    }

    // -------------------------------------------------------------------------
    // Ostrzeżenia
    // -------------------------------------------------------------------------

    /**
     * Uruchamia cykliczne pobieranie ostrzeżeń.
     * Pierwsze pobranie następuje natychmiast, kolejne co warningsRefreshInterval sekund.
     */
    public void scheduleWarnings() {
        if (!config.isWarningsEnabled()) {
            log.info("Pobieranie ostrzeżeń wyłączone w konfiguracji");
            return;
        }

        if (warningTask != null && !warningTask.isDone()) {
            warningTask.cancel(false);
        }

        int intervalSecs = parseIntOrDefault(
                config.getRaw("warnings.refresh.interval.seconds"), 600);

        warningTask = executor.scheduleAtFixedRate(
                this::runWarningCycle,
                0,
                intervalSecs,
                TimeUnit.SECONDS
        );

        log.info("Zaplanowano pobieranie ostrzeżeń co {} s", intervalSecs);
    }

    /**
     * Jeden cykl pobierania i zapisu ostrzeżeń.
     * Wywoływany przez executor — nie rzuca wyjątków.
     */
    private void runWarningCycle() {
        log.debug("Start cyklu ostrzeżeń");
        try {
            List<Warning> all = warningService.fetchAllWarnings();

            WarningLevel minLevel = config.getWarningsFilterLevel();
            List<Warning> filtered = warningService.filterByMinLevel(all, minLevel);

            // Logowanie krytycznych alertów
            filtered.forEach(w -> AppLogger.logWarningDetected(
                    w.getLevel().name(),
                    w.getType().name(),
                    w.getMessage()));

            repository.saveAllWarnings(filtered);

            // Czyszczenie wygasłych
            int deleted = repository.deleteExpiredWarnings();
            if (deleted > 0) {
                log.debug("Usunięto {} wygasłych ostrzeżeń", deleted);
            }

            notifyWarningListeners(filtered);
            log.info("Cykl ostrzeżeń zakończony — aktywnych: {}", filtered.size());

        } catch (Exception e) {
            log.error("Błąd w cyklu ostrzeżeń: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Zarządzanie listenerami
    // -------------------------------------------------------------------------

    /**
     * Rejestruje odbiorcę wyników cykli danych stacji.
     * Listener zostaje przekazany do wszystkich przyszłych FetchTask.
     *
     * @param listener odbiorca do zarejestrowania
     */
    public void addFetchListener(FetchTask.FetchListener listener) {
        if (listener != null) fetchListeners.add(listener);
    }

    /**
     * Rejestruje odbiorcę odświeżonych ostrzeżeń.
     *
     * @param listener odbiorca do zarejestrowania
     */
    public void addWarningListener(WarningListener listener) {
        if (listener != null) warningListeners.add(listener);
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /**
     * Zwraca liczbę aktywnie zaplanowanych stacji.
     *
     * @return liczba aktywnych zadań FetchTask
     */
    public int getActiveTaskCount() {
        return (int) tasks.values().stream()
                .filter(f -> !f.isDone() && !f.isCancelled())
                .count();
    }

    /**
     * Sprawdza czy stacja jest aktualnie zaplanowana.
     *
     * @param stationId identyfikator stacji
     * @return true jeśli zadanie istnieje i nie zostało anulowane
     */
    public boolean isScheduled(String stationId) {
        ScheduledFuture<?> f = tasks.get(stationId);
        return f != null && !f.isDone() && !f.isCancelled();
    }

    /**
     * Zwraca niemodyfikowalną kopię zbioru zaplanowanych ID stacji.
     *
     * @return zbiór identyfikatorów stacji z aktywnymi zadaniami
     */
    public java.util.Set<String> getScheduledStationIds() {
        return Collections.unmodifiableSet(tasks.keySet());
    }

    // -------------------------------------------------------------------------
    // Zamknięcie
    // -------------------------------------------------------------------------

    /**
     * Anuluje wszystkie zadania i zamyka pulę wątków.
     * Czeka maksymalnie 10 sekund na dokończenie trwających cykli.
     * Wywoływać w shutdown hooku aplikacji.
     */
    public void shutdown() {
        log.info("Zamykanie schedulera — aktywnych zadań: {}", getActiveTaskCount());

        tasks.values().forEach(f -> f.cancel(false));
        tasks.clear();

        if (warningTask != null) warningTask.cancel(false);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Scheduler nie zakończył w ciągu 10 s — wymuszam zamknięcie");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }

        log.info("Scheduler zamknięty");
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    private void notifyWarningListeners(List<Warning> warnings) {
        for (WarningListener listener : warningListeners) {
            try {
                listener.onWarningsRefreshed(warnings);
            } catch (Exception e) {
                log.warn("Błąd w WarningListener: {}", e.getMessage());
            }
        }
    }

    /**
     * Buduje pulę wątków schedulera z nazwanym ThreadFactory.
     * Wątki są demonami — nie blokują zamknięcia JVM.
     */
    private ScheduledExecutorService buildExecutor(String poolSizeRaw) {
        int poolSize = parseIntOrDefault(poolSizeRaw, 4);

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "eco-scheduler-" + counter.getAndIncrement());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) ->
                        log.error("Nieobsłużony wyjątek w wątku {}: ", thread.getName(), ex));
                return t;
            }
        };

        return Executors.newScheduledThreadPool(poolSize, factory);
    }

    private int parseIntOrDefault(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}