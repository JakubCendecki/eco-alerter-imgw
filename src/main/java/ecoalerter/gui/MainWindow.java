package ecoalerter.gui;

import ecoalerter.config.AppConfig;
import ecoalerter.gui.components.StatusBar;
import ecoalerter.gui.panels.DataViewPanel;
import ecoalerter.gui.panels.SettingsPanel;
import ecoalerter.gui.panels.StationManagerPanel;
import ecoalerter.gui.panels.WarningPanel;
import ecoalerter.scheduler.TaskSchedulerManager;
import ecoalerter.service.DataCollectionService;
import ecoalerter.service.NotificationService;
import ecoalerter.service.StationService;
import ecoalerter.service.WarningService;
import ecoalerter.util.AppLogger;
import ecoalerter.util.IconLoader;
import org.apache.logging.log4j.Logger;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Główne okno aplikacji EcoAlerter IMGW.
 *
 * Spaja wszystkie panele GUI w zakładki, wyświetla StatusBar u dołu okna
 * i synchronizuje go ze zdarzeniami z NotificationService. Okno nie wykonuje
 * żadnej logiki biznesowej samodzielnie — wyłącznie komponuje gotowe panele
 * i przekazuje im wymagane serwisy w konstruktorze.
 *
 * Zamknięcie okna nie kończy automatycznie procesu JVM — wywołujący
 * (klasa main aplikacji) powinien zarejestrować akcję zamknięcia przez
 * setOnCloseAction(), w której zamyka scheduler i repozytorium we właściwej
 * kolejności, a następnie kończy aplikację.
 */
public class MainWindow extends JFrame implements NotificationService.AppEventListener {

    private static final Logger log = AppLogger.get(MainWindow.class);

    private static final String APP_TITLE   = "EcoAlerter IMGW";
    private static final int    STATUS_REFRESH_INTERVAL_MS = 5_000;

    private final StationService         stationService;
    private final DataCollectionService  dataCollectionService;
    private final WarningService         warningService;
    private final NotificationService    notificationService;
    private final TaskSchedulerManager   scheduler;
    private final AppConfig              config;

    private final StatusBar             statusBar;
    private final StationManagerPanel   stationManagerPanel;
    private final DataViewPanel         dataViewPanel;
    private final WarningPanel          warningPanel;
    private final SettingsPanel         settingsPanel;

    private final Timer statusRefreshTimer;
    private Runnable    onCloseAction;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public MainWindow(StationService stationService,
                      DataCollectionService dataCollectionService,
                      WarningService warningService,
                      NotificationService notificationService,
                      TaskSchedulerManager scheduler,
                      AppConfig config) {
        super(APP_TITLE);

        this.stationService        = stationService;
        this.dataCollectionService = dataCollectionService;
        this.warningService        = warningService;
        this.notificationService   = notificationService;
        this.scheduler              = scheduler;
        this.config                 = config;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 700));
        applyAppIcon();

        this.statusBar            = new StatusBar();
        this.stationManagerPanel  = new StationManagerPanel(
                stationService, dataCollectionService, notificationService);
        this.dataViewPanel        = new DataViewPanel(stationService, dataCollectionService, notificationService, config);
        this.warningPanel         = new WarningPanel(warningService, notificationService);
        this.settingsPanel        = new SettingsPanel(config, dataCollectionService);

        settingsPanel.setOnRestartRequested(this::handleWindowClosing);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(null, stationManagerPanel);
        tabs.setTabComponentAt(0, buildTabComponent("Stacje", IconLoader.loadScaled("station.png", 18)));

        tabs.addTab(null, dataViewPanel);
        tabs.setTabComponentAt(1, buildTabComponent("Dane", IconLoader.loadScaled("data.png", 18)));

        tabs.addTab(null, warningPanel);
        tabs.setTabComponentAt(2, buildTabComponent("Ostrzeżenia", IconLoader.loadScaled("warning.png", 18)));

        tabs.addTab(null, settingsPanel);
        tabs.setTabComponentAt(3, buildTabComponent("Ustawienia", IconLoader.loadScaled("settings.png", 18)));

        add(tabs, java.awt.BorderLayout.CENTER);
        add(statusBar, java.awt.BorderLayout.SOUTH);

        notificationService.addListener(this);

        this.statusRefreshTimer = new Timer(STATUS_REFRESH_INTERVAL_MS, e -> refreshStatusBarCounts());
        statusRefreshTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClosing();
            }
        });

        refreshStatusBarCounts();
        pack();
        setLocationRelativeTo(null);
    }

    // -------------------------------------------------------------------------
    // Ikona aplikacji
    // -------------------------------------------------------------------------

    /**
     * Ustawia ikonę okna (widoczną w tytule okna i na pasku zadań systemu)
     * z app-icon.png. Brak pliku jest obsługiwany bezpiecznie przez
     * IconLoader — okno po prostu zostaje z domyślną ikoną Swing.
     */
    private void applyAppIcon() {
        ImageIcon icon = IconLoader.load("app-icon.png");
        if (icon != null) {
            setIconImage(icon.getImage());
        }
    }

    // -------------------------------------------------------------------------
    // Komponenty zakładek (ikona + tekst z większym odstępem niż domyślnie)
    // -------------------------------------------------------------------------

    /**
     * Buduje niestandardowy komponent zakładki z ikoną i tekstem rozdzielonymi
     * widocznym odstępem. Domyślne renderowanie JTabbedPane.addTab(title, icon, ...)
     * sklejało ikonę z tekstem bardzo blisko — własny komponent z FlowLayout
     * daje pełną kontrolę nad odstępem (12px).
     *
     * @param title tytuł zakładki
     * @param icon  ikona zakładki; null jest dozwolone — wtedy widoczny jest tylko tekst
     * @return panel do ustawienia przez tabs.setTabComponentAt(...)
     */
    private JPanel buildTabComponent(String title, Icon icon) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        panel.setOpaque(false);

        if (icon != null) {
            panel.add(new JLabel(icon));
        }
        panel.add(new JLabel(title));

        return panel;
    }

    // -------------------------------------------------------------------------
    // Uruchamianie usług w tle
    // -------------------------------------------------------------------------

    /**
     * Uruchamia harmonogram dla wszystkich aktywnych stacji zapisanych
     * w repozytorium oraz cykliczne pobieranie ostrzeżeń.
     *
     * Wywołać raz po setVisible(true), z klasy main aplikacji.
     * Operacja wykonuje odczyt z repozytorium w tle, więc nie blokuje EDT.
     */
    public void startServices() {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                stationService.scheduleAllActive();
                scheduler.scheduleWarnings();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    log.info("Harmonogram uruchomiony — aktywnych zadań: {}",
                            scheduler.getActiveTaskCount());
                } catch (Exception e) {
                    log.error("Błąd uruchamiania harmonogramu: {}", e.getMessage());
                    JOptionPane.showMessageDialog(MainWindow.this,
                            "Nie udało się uruchomić harmonogramu:\n" + e.getMessage(),
                            "Błąd startu", JOptionPane.ERROR_MESSAGE);
                } finally {
                    refreshStatusBarCounts();
                }
            }
        }.execute();
    }

    /**
     * Rejestruje akcję wykonywaną przy zamknięciu okna (np. zamknięcie
     * schedulera, repozytorium, zapis konfiguracji na dysk, System.exit).
     * Bez ustawienia tej akcji okno jedynie się ukryje bez zatrzymania
     * wątków schedulera.
     *
     * @param action akcja zamknięcia wywoływana na EDT
     */
    public void setOnCloseAction(Runnable action) {
        this.onCloseAction = action;
    }

    // -------------------------------------------------------------------------
    // NotificationService.AppEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onEvent(NotificationService.AppEvent event) {
        switch (event.getType()) {
            case DATA_UPDATED -> {
                statusBar.setLastSync(LocalDateTime.now());
                refreshStatusBarCounts();
            }
            case STATION_ERROR -> refreshStatusBarCounts();
            case WARNINGS_REFRESHED -> updateWarningSummaryFromEvent(event);
            case WARNING_DETECTED -> { /* obsłużone już przez WarningPanel i AlertBadge */ }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateWarningSummaryFromEvent(NotificationService.AppEvent event) {
        Object payload = event.getPayload();
        if (payload instanceof List<?>) {
            List<ecoalerter.model.Warning> warnings =
                    (List<ecoalerter.model.Warning>) payload;
            statusBar.setWarningSummary(WarningService.WarningSummary.from(warnings));
        }
    }

    // -------------------------------------------------------------------------
    // Odświeżanie paska statusu
    // -------------------------------------------------------------------------

    private void refreshStatusBarCounts() {
        statusBar.setActiveStations(scheduler.getActiveTaskCount());
        statusBar.setCriticalStations(notificationService.getCriticalStationCount());
    }

    // -------------------------------------------------------------------------
    // Zamykanie okna
    // -------------------------------------------------------------------------

    private void handleWindowClosing() {
        log.info("Zamykanie głównego okna aplikacji");

        statusRefreshTimer.stop();
        notificationService.removeListener(this);

        stationManagerPanel.dispose();
        dataViewPanel.dispose();
        warningPanel.dispose();
        statusBar.dispose();

        if (onCloseAction != null) {
            onCloseAction.run();
        } else {
            log.warn("Brak zarejestrowanej akcji zamknięcia — okno zostanie tylko ukryte");
            dispose();
        }
    }
}