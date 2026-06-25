package ecoalerter.gui;

import ecoalerter.config.AppConfig;
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
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Główne okno aplikacji EcoAlerter IMGW.
 *
 * Spaja wszystkie panele GUI w zakładki. Okno nie wykonuje żadnej logiki
 * biznesowej samodzielnie — wyłącznie komponuje gotowe panele i przekazuje
 * im wymagane serwisy w konstruktorze.
 *
 * Tu też ustawia się „kable" między panelami, które same się nie znają —
 * np. callback z SettingsPanel do DataViewPanel po zmianie zakresu danych.
 *
 * Zamknięcie okna nie kończy automatycznie procesu JVM — wywołujący
 * (klasa main aplikacji) powinien zarejestrować akcję zamknięcia przez
 * {@link #setOnCloseAction(Runnable)}, w której zamyka scheduler i repozytorium
 * we właściwej kolejności, a następnie kończy aplikację.
 */
public class MainWindow extends JFrame {
	private static final long serialVersionUID = 8883166054555429685L;

	private static final Logger log = AppLogger.get(MainWindow.class);

    private static final String APP_TITLE = "ECO Alert";

    private final StationService         stationService;
    private final DataCollectionService  dataCollectionService;
    private final WarningService         warningService;
    private final NotificationService    notificationService;
    private final TaskSchedulerManager   scheduler;
    private final AppConfig              config;

    private final StationManagerPanel   stationManagerPanel;
    private final DataViewPanel         dataViewPanel;
    private final WarningPanel          warningPanel;
    private final SettingsPanel         settingsPanel;

    private Runnable onCloseAction;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    /**
     * Buduje okno aplikacji i wszystkie zakładki.
     *
     * @param stationService        serwis stacji
     * @param dataCollectionService serwis danych
     * @param warningService        serwis ostrzeżeń
     * @param notificationService   szyna zdarzeń aplikacji
     * @param scheduler             zarządca harmonogramu cyklicznych zadań
     * @param config                konfiguracja aplikacji
     */
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
        this.scheduler             = scheduler;
        this.config                = config;

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 700));
        applyAppIcon();

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

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClosing();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    // -------------------------------------------------------------------------
    // Ikona aplikacji
    // -------------------------------------------------------------------------

    /**
     * Ustawia ikonę okna (widoczną w tytule okna i na pasku zadań systemu)
     * z {@code app-icon.png}.
     */
    private void applyAppIcon() {
        ImageIcon icon = IconLoader.load("app-icon.png");
        if (icon != null) {
            setIconImage(icon.getImage());
        }
    }

    // -------------------------------------------------------------------------
    // Komponenty zakładek
    // -------------------------------------------------------------------------

    /**
     * Buduje niestandardowy komponent zakładki z ikoną i tekstem rozdzielonymi
     * widocznym odstępem.
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
                }
            }
        }.execute();
    }

    /**
     * Rejestruje akcję wykonywaną przy zamknięciu okna.
     */
    public void setOnCloseAction(Runnable action) {
        this.onCloseAction = action;
    }

    // -------------------------------------------------------------------------
    // Zamykanie okna
    // -------------------------------------------------------------------------

    /**
     * Wykonuje sekwencję zamknięcia okna: woła {@code dispose()} na zakładkach
     * (żeby one wyczyściły swoje subskrypcje), a na końcu uruchamia akcję
     * zewnętrzną zarejestrowaną przez {@link #setOnCloseAction}.
     */
    private void handleWindowClosing() {
        log.info("Zamykanie głównego okna aplikacji");

        stationManagerPanel.dispose();
        dataViewPanel.dispose();
        warningPanel.dispose();

        if (onCloseAction != null) {
            onCloseAction.run();
        } else {
            log.warn("Brak zarejestrowanej akcji zamknięcia — okno zostanie tylko ukryte");
            dispose();
        }
    }
}