package ecoalerter;

import ecoalerter.api.HydroApiService;
import ecoalerter.api.ImgwApiClient;
import ecoalerter.api.MeteoApiService;
import ecoalerter.api.WarningApiService;
import ecoalerter.config.AppConfig;
import ecoalerter.gui.MainWindow;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceManager;
import ecoalerter.scheduler.ScheduleConfig;
import ecoalerter.scheduler.TaskSchedulerManager;
import ecoalerter.service.DataCollectionService;
import ecoalerter.service.NotificationService;
import ecoalerter.service.StationService;
import ecoalerter.service.WarningService;
import ecoalerter.util.AppLogger;
import ecoalerter.util.PathResolver;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Punkt wejścia aplikacji EcoAlerter IMGW.
 *
 * Odpowiada za pełną sekwencję startową: wczytanie konfiguracji, utworzenie
 * katalogów roboczych, inicjalizację warstwy persystencji, klientów API,
 * schedulera oraz serwisów biznesowych, a następnie zbudowanie i wyświetlenie
 * głównego okna GUI na wątku EDT.
 *
 * Zapewnia też bezpieczne zamknięcie aplikacji — zarówno przy normalnym
 * zamknięciu okna, jak i przy przerwaniu procesu (Ctrl+C, kill) poprzez
 * Runtime shutdown hook. Logika zamknięcia jest idempotentna — wywołanie
 * jej dwukrotnie (np. przez okno i przez hook równocześnie) nie powoduje
 * podwójnego zamknięcia repozytorium ani schedulera.
 *
 * Argumenty wiersza poleceń:
 * --config ścieżka/do/app.properties — używa niestandardowego pliku
 * konfiguracyjnego zamiast domyślnego app.properties z katalogu roboczego.
 */
public final class EcoAlerterApp {

    private static final Logger log = AppLogger.get(EcoAlerterApp.class);
    private static final String VERSION = "1.0.0";

    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // Referencje trzymane na poziomie aplikacji, potrzebne przy zamykaniu
    private static volatile DataRepository       repository;
    private static volatile TaskSchedulerManager scheduler;
    private static volatile ScheduleConfig       scheduleConfig;
    private static volatile PathResolver         pathResolver;
    
    private static FileLock lock;
    private static FileChannel channel;

    // -------------------------------------------------------------------------
    // main
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
    	if (!lockInstance()) {
    		System.exit(0);
    	}
    	
        AppLogger.logAppStart(VERSION);
        Runtime.getRuntime().addShutdownHook(new Thread(
                EcoAlerterApp::performShutdown, "eco-shutdown-hook"));

        try {
            AppConfig config = loadConfig(args);

            pathResolver = new PathResolver(config.getStorageFileDir(), config.getLogFileDir());
            pathResolver.createRequiredDirectories();

            repository = PersistenceManager.create(config, pathResolver);

            ImgwApiClient     apiClient          = new ImgwApiClient(config);
            MeteoApiService   meteoApiService    = new MeteoApiService(apiClient);
            HydroApiService   hydroApiService    = new HydroApiService(apiClient);
            WarningApiService warningApiService  = new WarningApiService(apiClient);

            scheduleConfig = ScheduleConfig.load(pathResolver.getScheduleConfigFile());

            scheduler = new TaskSchedulerManager(
                    config, scheduleConfig, meteoApiService, hydroApiService,
                    warningApiService, repository);

            NotificationService notificationService = new NotificationService();
            scheduler.addFetchListener(notificationService);
            scheduler.addWarningListener(notificationService);

            StationService stationService = new StationService(repository, scheduler, config);

            DataCollectionService dataCollectionService = new DataCollectionService(
                    meteoApiService, hydroApiService, repository, config.getDataTypeConfig());

            WarningService warningService = new WarningService(warningApiService, repository, config);

            launchGui(stationService, dataCollectionService, warningService,
                    notificationService, scheduler, config);

        } catch (Exception e) {
            log.error("Krytyczny błąd podczas startu aplikacji: {}", e.getMessage(), e);
            showStartupErrorAndExit(e);
        }
    }

    /**
     * Wymusza tylko 1 działającą instancję aplikacji
     */
    private static boolean lockInstance() {
        try {
            File file = new File(System.getProperty("user.home"), ".my_app.lock");
            channel = new RandomAccessFile(file, "rw").getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                return false;
            }
            // Opcjonalnie: usuń plik przy zamknięciu (choć i tak blokada zniknie po zabiciu procesu)
            file.deleteOnExit();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    // -------------------------------------------------------------------------
    // Konfiguracja
    // -------------------------------------------------------------------------

    /**
     * Wczytuje konfigurację — z niestandardowej ścieżki podanej argumentem
     * --config, lub z domyślnej lokalizacji gdy argument nie został podany.
     */
    private static AppConfig loadConfig(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                Path customPath = Path.of(args[i + 1]);
                log.info("Używam niestandardowego pliku konfiguracyjnego: {}", customPath);
                return AppConfig.load(customPath);
            }
        }
        return AppConfig.load();
    }

    // -------------------------------------------------------------------------
    // Budowa i wyświetlenie GUI
    // -------------------------------------------------------------------------

    private static void launchGui(StationService stationService,
                                  DataCollectionService dataCollectionService,
                                  WarningService warningService,
                                  NotificationService notificationService,
                                  TaskSchedulerManager scheduler,
                                  AppConfig config) {
        SwingUtilities.invokeLater(() -> {
            applyLookAndFeel(config);

            MainWindow window = new MainWindow(
                    stationService, dataCollectionService, warningService,
                    notificationService, scheduler, config);

            window.setOnCloseAction(() -> {
                window.dispose();
                performShutdown();
                System.exit(0);
            });

            window.setVisible(true);
            window.startServices();

            log.info("Interfejs graficzny uruchomiony");
        });
    }

    /**
     * Ustawia wygląd interfejsu wg klucza gui.look.and.feel z konfiguracji.
     * Przy błędzie loguje ostrzeżenie i zostaje przy domyślnym wyglądzie Swing —
     * nigdy nie przerywa startu aplikacji z powodu niedostępnego Look and Feel.
     */
    private static void applyLookAndFeel(AppConfig config) {
        String requested = config.getRaw("gui.look.and.feel");

        try {
            if ("system".equalsIgnoreCase(requested)) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                return;
            }

            if ("nimbus".equalsIgnoreCase(requested) || requested.isBlank()) {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equalsIgnoreCase(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        return;
                    }
                }
            }

            // Nieznana nazwa albo Nimbus niedostępny na tej platformie — fallback systemowy
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        } catch (Exception e) {
            log.warn("Nie udało się ustawić Look and Feel '{}': {} — używam domyślnego Swing",
                    requested, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Zamykanie aplikacji
    // -------------------------------------------------------------------------

    /**
     * Zamyka aplikację w bezpiecznej kolejności: zapisuje konfigurację
     * harmonogramu, zatrzymuje scheduler, zamyka repozytorium.
     *
     * Idempotentne — wywołane więcej niż jeden raz (np. równocześnie przez
     * zamknięcie okna i przez Runtime shutdown hook) wykona realną pracę
     * tylko przy pierwszym wywołaniu.
     */
    private static void performShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return; // zamknięcie już w toku lub zakończone
        }

        log.info("Zatrzymywanie aplikacji EcoAlerter IMGW...");

        try {
            if (scheduleConfig != null && pathResolver != null) {
                scheduleConfig.save(pathResolver.getScheduleConfigFile());
            }
        } catch (Exception e) {
            log.warn("Nie udało się zapisać konfiguracji harmonogramu: {}", e.getMessage());
        }

        if (scheduler != null) {
            scheduler.shutdown();
        }

        if (repository != null) {
            repository.close();
        }

        AppLogger.logAppStop("zamknięcie aplikacji");
    }

    // -------------------------------------------------------------------------
    // Obsługa błędów startu
    // -------------------------------------------------------------------------

    /**
     * Wyświetla okno dialogowe z opisem błędu krytycznego i kończy proces
     * z kodem wyjścia 1. Wywoływane wyłącznie gdy aplikacja nie zdołała
     * poprawnie zainicjalizować warstwy danych lub komunikacji z API —
     * w takim stanie GUI nie może bezpiecznie wystartować.
     */
    private static void showStartupErrorAndExit(Exception e) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                "Nie udało się uruchomić aplikacji:\n" + e.getMessage() +
                "\n\nSprawdź plik logów (logs/eco-alerter-imgw-errors.log) " +
                "oraz konfigurację app.properties.",
                "Błąd krytyczny — EcoAlerter IMGW",
                JOptionPane.ERROR_MESSAGE));

        // Czekamy chwilę, by dialog miał szansę się wyrenderować przed exit
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        System.exit(1);
    }

    // -------------------------------------------------------------------------
    // Konstruktor prywatny
    // -------------------------------------------------------------------------

    private EcoAlerterApp() {
        // klasa wejściowa — brak instancji
    }
}