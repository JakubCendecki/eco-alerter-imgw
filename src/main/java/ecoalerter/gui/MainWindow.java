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
 * Spaja wszystkie panele GUI w zakładki, wyświetla {@link StatusBar} u dołu
 * okna i synchronizuje go ze zdarzeniami z {@link NotificationService}.
 * Okno nie wykonuje żadnej logiki biznesowej samodzielnie — wyłącznie komponuje
 * gotowe panele i przekazuje im wymagane serwisy w konstruktorze.
 *
 * Tu też ustawia się „kable" między panelami, które same się nie znają —
 * np. callback z SettingsPanel do DataViewPanel po zmianie zakresu danych.
 *
 * Zamknięcie okna nie kończy automatycznie procesu JVM — wywołujący
 * (klasa main aplikacji) powinien zarejestrować akcję zamknięcia przez
 * {@link #setOnCloseAction(Runnable)}, w której zamyka scheduler i repozytorium
 * we właściwej kolejności, a następnie kończy aplikację.
 */
public class MainWindow extends JFrame implements NotificationService.AppEventListener {
	private static final long serialVersionUID = 6506498032663738829L;

	private static final Logger log = AppLogger.get(MainWindow.class);

    private static final String APP_TITLE = "ECO Alert";
    private static final int    STATUS_REFRESH_INTERVAL_MS = 5_000;

    private final StationService         stationService;
    private final NotificationService    notificationService;
    private final TaskSchedulerManager   scheduler;

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

    /**
     * Buduje okno aplikacji i wszystkie zakładki.
     *
     * @param stationService        serwis stacji
     * @param notificationService   szyna zdarzeń aplikacji
     * @param scheduler             zarządca harmonogramu cyklicznych zadań
     */
    public MainWindow(StationService stationService,
                      DataCollectionService dataCollectionService,
                      WarningService warningService,
                      NotificationService notificationService,
                      TaskSchedulerManager scheduler,
                      AppConfig config) {
        super(APP_TITLE);

        this.stationService        = stationService;
        this.notificationService   = notificationService;
        this.scheduler             = scheduler;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 700));
        applyAppIcon();

        this.statusBar            = new StatusBar();
        this.stationManagerPanel  = new StationManagerPanel(
                stationService, dataCollectionService, notificationService);
        this.dataViewPanel        = new DataViewPanel(
                stationService, dataCollectionService, notificationService, config);
        this.warningPanel         = new WarningPanel(warningService, notificationService);
        this.settingsPanel        = new SettingsPanel(
                config, dataCollectionService, stationService, notificationService);

        // Kable między panelami:
        //  - restart → zamknięcie okna w bezpiecznej kolejności,
        //  - zmiana zakresu monitorowanych danych / czyszczenie historii →
        //    natychmiastowe przeładowanie tabeli w „Dane" (kolumny dopasowane
        //    do nowego DataTypeConfig, dane przeładowane z repozytorium).
        settingsPanel.setOnRestartRequested(this::handleWindowClosing);
        settingsPanel.setOnDataTypeConfigChanged(dataViewPanel::refreshCurrentView);

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
     * z {@code app-icon.png}. Brak pliku jest obsługiwany bezpiecznie przez
     * {@link IconLoader} — okno po prostu zostaje z domyślną ikoną Swing.
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
     * widocznym odstępem. Domyślne renderowanie
     * {@code JTabbedPane.addTab(title, icon, ...)} sklejało ikonę z tekstem
     * bardzo blisko — własny komponent z {@link FlowLayout} daje pełną
     * kontrolę nad odstępem (12px).
     *
     * @param title tytuł zakładki
     * @param icon  ikona zakładki; null jest dozwolone — wtedy widoczny
     *              jest tylko tekst
     * @return panel do ustawienia przez {@code tabs.setTabComponentAt(...)}
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
     * Wywołać raz po {@code setVisible(true)}, z klasy main aplikacji.
     * Operacja wykonuje odczyt z repozytorium w tle (SwingWorker), więc
     * nie blokuje EDT.
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
     * schedulera, repozytorium, zapis konfiguracji na dysk, {@code System.exit}).
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

    /**
     * Obsługuje zdarzenia globalne, które wpływają na pasek statusu:
     * {@code DATA_UPDATED} aktualizuje znacznik ostatniej synchronizacji
     * i liczniki, {@code STATION_ERROR} odświeża liczniki krytycznych stacji,
     * {@code WARNINGS_REFRESHED} aktualizuje sumaryczne podsumowanie ostrzeżeń.
     * {@code WARNING_DETECTED} jest pomijane — obsługuje je już bezpośrednio
     * {@code WarningPanel} i {@code AlertBadge}.
     */
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
            default -> throw new IllegalArgumentException("Unexpected value: " + event.getType());
        }
    }

    /**
     * Wyciąga listę ostrzeżeń z payloadu zdarzenia {@code WARNINGS_REFRESHED}
     * i odświeża sumę pokazywaną na pasku statusu.
     * Bezpiecznie ignoruje payloady innego typu niż {@link List}.
     */
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

    /**
     * Odświeża liczniki na pasku statusu (aktywne stacje, stacje krytyczne).
     * Wywoływane co {@value #STATUS_REFRESH_INTERVAL_MS} ms przez Timer
     * oraz po każdym istotnym zdarzeniu (np. {@code DATA_UPDATED}).
     */
    private void refreshStatusBarCounts() {
        statusBar.setActiveStations(scheduler.getActiveTaskCount());
        statusBar.setCriticalStations(notificationService.getCriticalStationCount());
    }

    // -------------------------------------------------------------------------
    // Zamykanie okna
    // -------------------------------------------------------------------------

    /**
     * Wykonuje sekwencję zamknięcia okna: zatrzymuje timer, wyrejestrowuje
     * listenery z {@link NotificationService}, woła {@code dispose()}
     * na zakładkach (żeby i one wyczyściły swoje subskrypcje), a na końcu
     * uruchamia akcję zewnętrzną zarejestrowaną przez {@link #setOnCloseAction}.
     *
     * Gdy akcja zewnętrzna nie jest ustawiona, okno jest jedynie ukrywane
     * przez standardowe {@code dispose()} — wątki schedulera pozostaną wtedy
     * aktywne, więc klasa main powinna zawsze ustawić akcję zamknięcia.
     */
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