package ecoalerter.gui.panels;

import ecoalerter.config.AppConfig;
import ecoalerter.config.PersistenceMode;
import ecoalerter.service.DataCollectionService;
import ecoalerter.service.NotificationService;
import ecoalerter.service.StationService;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.FlowLayout;

/**
 * Panel ustawień aplikacji.
 *
 * Sekcje:
 * <ul>
 *   <li><b>Persystencja danych</b> — wybór trybu (Plik / Baza). Wymaga restartu.</li>
 *   <li><b>Zakres monitorowanych danych</b> — które pola pomiarów pokazywać
 *       w widoku. Stosowane natychmiast (DataViewPanel czyta na bieżąco).</li>
 *   <li><b>Harmonogram</b> — domyślny interwał dla nowo planowanych stacji.</li>
 *   <li><b>API IMGW</b> — timeout, liczba ponowień.</li>
 *   <li><b>Dziennik zdarzeń</b> — poziom logowania.</li>
 *   <li><b>Czyszczenie historii</b> — usunięcie starych pomiarów lub wszystkich danych.</li>
 *   <li><b>Reset</b> — przywrócenie ustawień fabrycznych (wymaga restartu).</li>
 * </ul>
 *
 * Wzorzec dla zmian wymagających restartu: dialog Yes/No → przy „Tak" zapis
 * i wywołanie zarejestrowanej akcji restartu, przy „Nie" przywrócenie kontrolek
 * (revert) bez zapisu.
 */
public class SettingsPanel extends JPanel {
	private static final long serialVersionUID = 2781647122217999954L;

	private static final Logger log = AppLogger.get(SettingsPanel.class);

    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 30;

    private final AppConfig              config;
    private final DataCollectionService  dataCollectionService;
    private final StationService         stationService;
    private final NotificationService    notificationService;

    private JRadioButton fileModeRadio;
    private JRadioButton dbModeRadio;

    private JCheckBox temperatureBox;
    private JCheckBox windBox;
    private JCheckBox precipitationBox;
    private JCheckBox waterLevelBox;
    private JCheckBox waterTemperatureBox;

    private JSlider  defaultIntervalSlider;

    private JSpinner                apiTimeoutSpinner;
    private JSpinner                apiRetrySpinner;
    private JComboBox<String>       logLevelCombo;
    private JSpinner                cleanupDaysSpinner;

    private Runnable onRestartRequested;

    /**
     * Wywoływany po zatwierdzeniu zmian w sekcji „Zakres monitorowanych danych".
     * Pozwala innym panelom (DataViewPanel) odświeżyć swój widok bez restartu —
     * np. od razu pokazać/ukryć kolumnę temperatury po przełączeniu jej checkboxa.
     * Wstrzykiwany z MainWindow, bo SettingsPanel nie powinien znać DataViewPanel.
     */
    private Runnable onDataTypeConfigChanged;

    /**
     * @param config                konfiguracja aplikacji — czytana i zapisywana
     *                              przez tę zakładkę
     * @param dataCollectionService serwis danych — używany przez „Usuń dane
     *                              starsze niż..."
     * @param stationService        serwis stacji — używany przez „Wyczyść
     *                              wszystkie dane" (musi usunąć też stacje
     *                              i zatrzymać scheduler)
     * @param notificationService   szyna zdarzeń — po wyczyszczeniu danych
     *                              wysyłamy {@code STATIONS_CHANGED}, żeby
     *                              inne panele odświeżyły swoje widoki
     */
    public SettingsPanel(AppConfig config,
                         DataCollectionService dataCollectionService,
                         StationService stationService,
                         NotificationService notificationService) {
        this.config                = config;
        this.dataCollectionService = dataCollectionService;
        this.stationService        = stationService;
        this.notificationService   = notificationService;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildPersistenceSection());
        add(Box.createVerticalStrut(10));
        add(buildDataTypeSection());
        add(Box.createVerticalStrut(10));
        add(buildSchedulerSection());
        add(Box.createVerticalStrut(10));
        add(buildApiSection());
        add(Box.createVerticalStrut(10));
        add(buildLoggingSection());
        add(Box.createVerticalStrut(10));
        add(buildCleanupSection());
        add(Box.createVerticalStrut(10));
        add(buildResetSection());
        add(Box.createVerticalGlue());

        loadCurrentValues();
    }

    /**
     * Rejestruje akcję wykonywaną po potwierdzeniu restartu aplikacji
     * — typowo zamknięcie głównego okna w bezpiecznej kolejności.
     * Wywoływane przez MainWindow po skonstruowaniu panelu.
     *
     * @param action akcja restartu; null wyłącza automatyczne zamknięcie
     */
    public void setOnRestartRequested(Runnable action) {
        this.onRestartRequested = action;
    }

    /**
     * Rejestruje akcję wywoływaną po zatwierdzeniu zmian zakresu monitorowanych
     * danych — typowo {@code dataViewPanel::refreshCurrentView}.
     * Bez ustawienia panel nadal działa, ale DataViewPanel nie odświeży się
     * automatycznie (kolumny zaktualizują się dopiero przy następnym kliknięciu
     * „Odśwież" lub zmianie wybranej stacji).
     */
    public void setOnDataTypeConfigChanged(Runnable action) {
        this.onDataTypeConfigChanged = action;
    }

    // -------------------------------------------------------------------------
    // Sekcja: persystencja danych (wymaga restartu)
    // -------------------------------------------------------------------------

    /** Buduje sekcję wyboru trybu zapisu (Plik vs Baza danych). */
    private JPanel buildPersistenceSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        ButtonGroup modeGroup = new ButtonGroup();
        fileModeRadio = new JRadioButton("Plik (JSON)");
        dbModeRadio   = new JRadioButton("Baza danych (SQLite)");
        modeGroup.add(fileModeRadio);
        modeGroup.add(dbModeRadio);

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        modeRow.add(new JLabel("Tryb zapisu:"));
        modeRow.add(fileModeRadio);
        modeRow.add(dbModeRadio);

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyPersistence());

        JLabel note = new JLabel("Zmiana wymaga restartu aplikacji.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(java.awt.Color.GRAY);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        buttonRow.add(applyButton);
        buttonRow.add(note);

        content.add(modeRow);
        content.add(buttonRow);

        return wrapTitled("Sposób zapisywania danych", content);
    }

    /**
     * Obsługa kliknięcia „Zastosuj" w sekcji persystencji. Jeśli wybór nie
     * różni się od bieżącego trybu — komunikat „brak zmian" i koniec.
     * W przeciwnym razie pyta o potwierdzenie restartu.
     */
    private void onApplyPersistence() {
        PersistenceMode chosenMode  = fileModeRadio.isSelected()
                ? PersistenceMode.FILE : PersistenceMode.DATABASE;
        PersistenceMode currentMode = config.getPersistenceMode();

        if (chosenMode == currentMode) {
            JOptionPane.showMessageDialog(this, "Brak zmian do zastosowania.",
                    "Informacja", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        confirmRestartOrRevert(
                () -> {
                    config.setRaw("persistence.mode", chosenMode.name());
                    AppLogger.logConfigChange("persistence.mode", currentMode.name(), chosenMode.name());
                },
                this::loadPersistenceRadios
        );
    }

    /** Synchronizuje radio buttony z bieżącą wartością {@code config.getPersistenceMode()}. */
    private void loadPersistenceRadios() {
        PersistenceMode mode = config.getPersistenceMode();
        fileModeRadio.setSelected(mode == PersistenceMode.FILE);
        dbModeRadio.setSelected(mode == PersistenceMode.DATABASE);
    }

    // -------------------------------------------------------------------------
    // Sekcja: zakres monitorowanych danych (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    /** Buduje sekcję checkboxów decydujących, które pola pomiarów pokazywać w widoku. */
    private JPanel buildDataTypeSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 4));

        temperatureBox       = plainCheckbox("Temperatura");
        windBox              = plainCheckbox("Prędkość wiatru");
        precipitationBox     = plainCheckbox("Opady");
        waterLevelBox        = plainCheckbox("Stan wody");
        waterTemperatureBox  = plainCheckbox("Temperatura wody");

        grid.add(boldLabel("Dane meteo"));
        grid.add(boldLabel("Dane hydro"));
        grid.add(temperatureBox);
        grid.add(waterLevelBox);
        grid.add(windBox);
        grid.add(waterTemperatureBox);
        grid.add(precipitationBox);
        grid.add(new JLabel());

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDataTypeConfig());


        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        buttonRow.add(applyButton);

        content.add(grid);
        content.add(buttonRow);

        return wrapTitled("Zakres monitorowanych danych", content);
    }

    /**
     * Zapisuje zaznaczone checkboxy do konfiguracji w pamięci (i automatycznie
     * na dysk przez AppConfig.setRaw). Zmiany są stosowane natychmiast — zarówno
     * scheduler (który teraz ZAWSZE zapisuje wszystkie pola dostarczone przez API),
     * jak i DataViewPanel (który przy każdym odświeżeniu czyta DataTypeConfig
     * na bieżąco z AppConfig) nie potrzebują restartu.
     *
     * Jeśli żaden checkbox nie zmienił swojego stanu względem zapisanej konfiguracji,
     * informujemy o tym i nic nie robimy — żeby nie spamować pliku konfiguracji
     * identycznymi wartościami ani nie wprowadzać użytkownika w błąd komunikatem
     * o „zastosowaniu zmian", których nie było.
     */
    private void onApplyDataTypeConfig() {
        var current = config.getDataTypeConfig();
        boolean hasChanges =
                current.isTemperatureEnabled()      != temperatureBox.isSelected()
             || current.isWindEnabled()             != windBox.isSelected()
             || current.isPrecipitationEnabled()    != precipitationBox.isSelected()
             || current.isWaterLevelEnabled()       != waterLevelBox.isSelected()
             || current.isWaterTemperatureEnabled() != waterTemperatureBox.isSelected();

        if (!hasChanges) {
            JOptionPane.showMessageDialog(this, "Brak zmian do zastosowania.",
                    "Informacja", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Pola data.meteo.enabled i data.hydro.enabled zostają na true — kategoria
        // jest efektywnie wyłączona, jeśli WSZYSTKIE jej checkboxy są odznaczone
        // (i wtedy DataViewPanel po prostu nie pokazuje żadnej z jej kolumn).
        config.setRaw("data.meteo.enabled", "true");
        config.setRaw("data.hydro.enabled", "true");
        config.setRaw("data.meteo.temperature",    String.valueOf(temperatureBox.isSelected()));
        config.setRaw("data.meteo.wind",           String.valueOf(windBox.isSelected()));
        config.setRaw("data.meteo.precipitation",  String.valueOf(precipitationBox.isSelected()));
        config.setRaw("data.hydro.waterLevel",       String.valueOf(waterLevelBox.isSelected()));
        config.setRaw("data.hydro.waterTemperature", String.valueOf(waterTemperatureBox.isSelected()));

        log.info("Zakres monitorowanych danych zaktualizowany");

        // Powiadom DataViewPanel, żeby przebudował kolumny i przeładował dane
        // dla aktualnie wybranej stacji — bez tego do następnego ręcznego
        // odświeżenia użytkownik widzi stary układ kolumn, mimo że zapis już
        // przeszedł.
        if (onDataTypeConfigChanged != null) {
            onDataTypeConfigChanged.run();
        }

        JOptionPane.showMessageDialog(this,
                "Zakres monitorowanych danych zaktualizowany.\n",
                "Zastosowano", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Synchronizuje checkboxy z bieżącym {@code config.getDataTypeConfig()}. */
    private void loadDataTypeCheckboxes() {
        var dataTypeConfig = config.getDataTypeConfig();
        temperatureBox.setSelected(dataTypeConfig.isTemperatureEnabled());
        windBox.setSelected(dataTypeConfig.isWindEnabled());
        precipitationBox.setSelected(dataTypeConfig.isPrecipitationEnabled());
        waterLevelBox.setSelected(dataTypeConfig.isWaterLevelEnabled());
        waterTemperatureBox.setSelected(dataTypeConfig.isWaterTemperatureEnabled());
    }

    // -------------------------------------------------------------------------
    // Sekcja: harmonogram — domyślny interwał (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    /**
     * Buduje sekcję z suwakiem domyślnego interwału dla nowo planowanych stacji.
     * Zakres slidera 5-30 minut — wartości spoza są clampowane przez
     * {@link #clampToMinuteSlider(int)}.
     */
    private JPanel buildSchedulerSection() {
        JPanel content = new JPanel(new java.awt.BorderLayout(8, 0));

        int currentMinutes = clampToMinuteSlider(config.getSchedulerDefaultIntervalSeconds() / 60);
        defaultIntervalSlider = new JSlider(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES, currentMinutes);
        defaultIntervalSlider.setMajorTickSpacing(5);
        defaultIntervalSlider.setSnapToTicks(true);
        defaultIntervalSlider.setPaintTicks(true);
        defaultIntervalSlider.setPaintLabels(true);
        defaultIntervalSlider.setPreferredSize(new java.awt.Dimension(320, 45));

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDefaultInterval());

        content.add(new JLabel("Jak często sprawdzać nowe stacje (min):"), java.awt.BorderLayout.WEST);
        content.add(defaultIntervalSlider, java.awt.BorderLayout.CENTER);
        content.add(applyButton, java.awt.BorderLayout.EAST);

        return wrapTitled("Harmonogram", content);
    }

    /**
     * Obsługa „Zastosuj" w sekcji harmonogramu. Zapisuje wartość w sekundach
     * do konfiguracji — kolejne planowanie zadań od razu używa nowej wartości.
     */
    private void onApplyDefaultInterval() {
        int    newMinutes = defaultIntervalSlider.getValue();
        int    newSeconds = newMinutes * 60;
        String oldValue   = config.getRaw("scheduler.default.interval.seconds");

        config.setRaw("scheduler.default.interval.seconds", String.valueOf(newSeconds));
        AppLogger.logConfigChange("scheduler.default.interval.seconds", oldValue,
                String.valueOf(newSeconds));

        JOptionPane.showMessageDialog(this,
                "Ustawiono: sprawdzanie nowych stacji co " + newMinutes + " min.\n" +
                "Obowiązuje od razu dla nowo planowanych stacji.",
                "Zastosowano", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Ogranicza wartość w minutach do zakresu obsługiwanego przez slider
     * ({@value #MIN_INTERVAL_MINUTES}-{@value #MAX_INTERVAL_MINUTES}).
     * Istniejące interwały zapisane przed wprowadzeniem slidera mogły
     * przekraczać ten zakres — są tu po prostu przycinane do nowego limitu.
     */
    private int clampToMinuteSlider(int minutes) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
    }

    // -------------------------------------------------------------------------
    // Sekcja: konfiguracja API (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    /** Buduje sekcję z timeoutem i liczbą ponowień dla żądań do API IMGW. */
    private JPanel buildApiSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 4));

        apiTimeoutSpinner = new JSpinner(new SpinnerNumberModel(
                config.getApiTimeoutSeconds(), 1, 120, 1));
        apiRetrySpinner = new JSpinner(new SpinnerNumberModel(
                config.getApiRetryCount(), 0, 10, 1));

        grid.add(new JLabel("Czas oczekiwania na odpowiedź (s):"));
        grid.add(apiTimeoutSpinner);
        grid.add(new JLabel("Liczba ponowień:"));
        grid.add(apiRetrySpinner);

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyApiSettings());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        buttonRow.add(applyButton);

        content.add(grid);
        content.add(buttonRow);

        return wrapTitled("Połączenie z serwerem IMGW", content);
    }

    /** Zapisuje timeout i liczbę ponowień do konfiguracji — kolejne żądania używają nowych wartości. */
    private void onApplyApiSettings() {
        int newTimeout = (Integer) apiTimeoutSpinner.getValue();
        int newRetries = (Integer) apiRetrySpinner.getValue();

        String oldTimeout = config.getRaw("api.timeout.seconds");
        String oldRetries = config.getRaw("api.retry.count");

        config.setRaw("api.timeout.seconds", String.valueOf(newTimeout));
        config.setRaw("api.retry.count", String.valueOf(newRetries));

        AppLogger.logConfigChange("api.timeout.seconds", oldTimeout, String.valueOf(newTimeout));
        AppLogger.logConfigChange("api.retry.count", oldRetries, String.valueOf(newRetries));

        JOptionPane.showMessageDialog(this,
                "Zapisano: czas oczekiwania " + newTimeout + " s, liczba ponowień " + newRetries + ".",
                "Zastosowano", JOptionPane.INFORMATION_MESSAGE);
    }

    // -------------------------------------------------------------------------
    // Sekcja: logowanie (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    /**
     * Buduje sekcję z wyborem poziomu logowania.
     * Zmiana jest stosowana natychmiast przez {@link AppLogger#setRootLevel(String)}
     * — nie wymaga restartu, nie wymaga przycisku „Zastosuj".
     */
    private JPanel buildLoggingSection() {
        JPanel panel = titledPanel("Dziennik zdarzeń", new GridLayout(1, 2, 8, 4));

        logLevelCombo = new JComboBox<>(new String[]{"TRACE", "DEBUG", "INFO", "WARN", "ERROR"});
        logLevelCombo.addActionListener(e -> {
            String selected = (String) logLevelCombo.getSelectedItem();
            if (selected != null) {
                try {
                    AppLogger.setRootLevel(selected);
                    config.setRaw("log.level", selected);
                } catch (IllegalArgumentException ex) {
                    log.warn("Nieprawidłowy poziom logowania: {}", selected);
                }
            }
        });

        panel.add(new JLabel("Poziom szczegółowości dziennika:"));
        panel.add(logLevelCombo);

        return panel;
    }

    // -------------------------------------------------------------------------
    // Sekcja: czyszczenie historii danych
    // -------------------------------------------------------------------------

    /**
     * Buduje sekcję czyszczenia z dwoma operacjami:
     * cleanup wg wieku (zachowaj N dni) i cleanup wszystkiego (z dwuetapowym
     * potwierdzeniem).
     */
    private JPanel buildCleanupSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        // --- Rząd 1: cleanup wg wieku ---
        JPanel byAgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        cleanupDaysSpinner = new JSpinner(new SpinnerNumberModel(90, 1, 3650, 30));
        JButton cleanupOldButton = new JButton("Usuń dane starsze niż...");
        cleanupOldButton.addActionListener(e -> onCleanupRequested());
        byAgeRow.add(new JLabel("Zachowaj dane z ostatnich (dni):"));
        byAgeRow.add(cleanupDaysSpinner);
        byAgeRow.add(cleanupOldButton);

        // --- Rząd 2: cleanup wszystkiego ---
        JPanel allRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton clearAllButton = new JButton("Wyczyść wszystkie dane pomiarowe...");
        clearAllButton.addActionListener(e -> onClearAllRequested());

        allRow.add(clearAllButton);

        content.add(byAgeRow);
        content.add(allRow);

        return wrapTitled("Czyszczenie historii danych", content);
    }

    /**
     * Obsługa „Wyczyść wszystkie dane...". Pyta o potwierdzenie dwuetapowo
     * — najpierw Yes/No, potem trzeba wpisać słowo „USUŃ" — żeby uniknąć
     * przypadkowego skasowania wszystkiego.
     *
     * Operacja kasuje WSZYSTKO oprócz ustawień aplikacji: stacje, ich
     * dane pomiarowe, ostrzeżenia, a także zatrzymuje wszystkie zaplanowane
     * fetchy w schedulerze (bez tego pula wątków próbowałaby dalej odpytywać
     * API dla nieistniejących już stacji). Po sukcesie odpala
     * {@link #onDataTypeConfigChanged} (czyszczenie zakładki Dane) oraz
     * {@code notifyStationsChanged()} (StationManagerPanel i DataViewPanel
     * przeładowują listę stacji).
     */
    private void onClearAllRequested() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Czy na pewno chcesz usunąć WSZYSTKIE stacje wraz z ich danymi\n" +
                "pomiarowymi i ostrzeżeniami? Operacji nie można cofnąć.\n\n" +
                "Ustawienia aplikacji pozostaną nietknięte.",
                "Potwierdzenie usunięcia wszystkich danych",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        String confirmation = (String) JOptionPane.showInputDialog(this,
                "Aby potwierdzić, wpisz słowo USUŃ:",
                "Ostatnie potwierdzenie",
                JOptionPane.WARNING_MESSAGE, null, null, "");
        if (confirmation == null || !"USUŃ".equals(confirmation.trim())) {
            JOptionPane.showMessageDialog(this,
                    "Operacja anulowana — dane pozostały nietknięte.",
                    "Anulowano", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // stationService.clearAll() ogarnia:
                //   - anulowanie zadań w schedulerze dla każdej stacji,
                //   - skasowanie plików pomiarów (meteo, hydro),
                //   - skasowanie plików ostrzeżeń,
                //   - skasowanie stations.json.
                stationService.clearAll();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    log.info("Wszystkie stacje i dane zostały wyczyszczone");

                    // Po wyczyszczeniu stacji — StationManagerPanel i DataViewPanel
                    // muszą przeładować swoją listę stacji. Wysyłka STATIONS_CHANGED
                    // załatwia oba (DataViewPanel już to słucha, StationManagerPanel
                    // — po fix-ie w onEvent też).
                    notificationService.notifyStationsChanged();

                    // Dodatkowo czyszczenie aktualnej zawartości tabeli Dane —
                    // to samo wywołanie co po zmianie zakresu monitorowanych danych.
                    if (onDataTypeConfigChanged != null) {
                        onDataTypeConfigChanged.run();
                    }

                    JOptionPane.showMessageDialog(SettingsPanel.this,
                            "Wszystkie stacje wraz z ich danymi i ostrzeżeniami zostały usunięte.",
                            "Zakończono", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    log.error("Błąd czyszczenia wszystkich danych: {}", e.getMessage(), e);
                    JOptionPane.showMessageDialog(SettingsPanel.this,
                            "Nie udało się wyczyścić wszystkich danych:\n" + e.getMessage(),
                            "Błąd", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Obsługa „Usuń dane starsze niż...". Pyta o potwierdzenie, a operacja
     * leci przez SwingWorker — nie blokuje EDT przy dużej historii.
     */
    private void onCleanupRequested() {
        int days = (Integer) cleanupDaysSpinner.getValue();

        int confirm = JOptionPane.showConfirmDialog(this,
                "Usunąć wszystkie dane pomiarowe starsze niż " + days + " dni?\n" +
                "Tej operacji nie można odwrócić.",
                "Potwierdzenie czyszczenia", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                dataCollectionService.cleanOldData(days);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    JOptionPane.showMessageDialog(SettingsPanel.this,
                            "Stare dane zostały usunięte.",
                            "Zakończono", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    log.error("Błąd czyszczenia starych danych: {}", e.getMessage());
                    JOptionPane.showMessageDialog(SettingsPanel.this,
                            "Błąd podczas czyszczenia danych:\n" + e.getMessage(),
                            "Błąd", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // -------------------------------------------------------------------------
    // Sekcja: reset ustawień (wymaga restartu)
    // -------------------------------------------------------------------------

    /** Buduje sekcję z przyciskiem resetującym wszystkie ustawienia do wartości domyślnych. */
    private JPanel buildResetSection() {
        JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));

        JButton resetButton = new JButton("Przywróć ustawienia domyślne");
        resetButton.addActionListener(e -> onResetSettings());

        JLabel note = new JLabel("Wymaga restartu aplikacji.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(java.awt.Color.GRAY);

        content.add(resetButton);
        content.add(note);

        return wrapTitled("Reset", content);
    }

    /**
     * Obsługa „Resetuj ustawienia". Po potwierdzeniu przywraca wszystkie
     * ustawienia do domyślnych i wywołuje akcję restartu — bez restartu
     * większość zmian (np. tryb persystencji) nie mogłaby zostać zastosowana.
     */
    private void onResetSettings() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reset przywróci wszystkie ustawienia do wartości domyślnych\n" +
                "i WYMAGA natychmiastowego zamknięcia aplikacji.\n" +
                "Trzeba będzie uruchomić ją ponownie ręcznie.\n\n" +
                "Czy kontynuować?",
                "Reset ustawień — wymagany restart aplikacji",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        config.resetToDefaults();

        if (onRestartRequested != null) {
            onRestartRequested.run();
        } else {
            log.warn("Brak zarejestrowanej akcji restartu — ustawienia zresetowane, " +
                     "ale aplikacja nie zostanie zamknięta automatycznie");
            loadCurrentValues();
            JOptionPane.showMessageDialog(this,
                    "Ustawienia zresetowane. Uruchom aplikację ponownie, aby zastosować zmiany.",
                    "Zresetowano", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // -------------------------------------------------------------------------
    // Wczytanie wartości początkowych z konfiguracji
    // -------------------------------------------------------------------------

    /**
     * Wczytuje wszystkie kontrolki ze stanu konfiguracji. Wywoływane w
     * konstruktorze, po resecie ustawień, oraz przy revercie zmian wymagających
     * restartu.
     */
    private void loadCurrentValues() {
        loadPersistenceRadios();
        loadDataTypeCheckboxes();
        logLevelCombo.setSelectedItem(config.getLogLevel());
        apiTimeoutSpinner.setValue(config.getApiTimeoutSeconds());
        apiRetrySpinner.setValue(config.getApiRetryCount());
        defaultIntervalSlider.setValue(clampToMinuteSlider(config.getSchedulerDefaultIntervalSeconds() / 60));
    }

    // -------------------------------------------------------------------------
    // Wspólny wzorzec: zmiana wymagająca restartu
    // -------------------------------------------------------------------------

    /**
     * Pyta użytkownika o potwierdzenie zmiany wymagającej restartu aplikacji.
     *
     * Po „Tak": wykonuje {@code applyAction} (zapis do AppConfig) i wywołuje
     * zarejestrowaną akcję restartu (zamknięcie aplikacji w bezpiecznej
     * kolejności) — użytkownik musi uruchomić aplikację ponownie ręcznie.
     *
     * Po „Nie": wykonuje {@code revertAction}, które przywraca poprzedni
     * stan kontrolek GUI bez zapisywania niczego do konfiguracji.
     *
     * @param applyAction  zapisuje nowe wartości do AppConfig
     * @param revertAction przywraca kontrolki GUI do stanu zgodnego z aktualną konfiguracją
     */
    private void confirmRestartOrRevert(Runnable applyAction, Runnable revertAction) {
        int choice = JOptionPane.showConfirmDialog(this,
                "Ta zmiana wymaga restartu aplikacji, aby w pełni zadziałać.\n" +
                "Czy zapisać zmiany i zamknąć aplikację teraz?\n" +
                "(Trzeba będzie uruchomić ją ponownie ręcznie)",
                "Wymagany restart aplikacji",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            applyAction.run();
            if (onRestartRequested != null) {
                onRestartRequested.run();
            } else {
                log.warn("Brak zarejestrowanej akcji restartu — zmiana zapisana, " +
                         "ale aplikacja nie zostanie zamknięta automatycznie");
                JOptionPane.showMessageDialog(this,
                        "Zmiana zapisana. Uruchom aplikację ponownie, aby ją zastosować.",
                        "Zapisano", JOptionPane.INFORMATION_MESSAGE);
            }
        } else {
            revertAction.run();
        }
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze (UI)
    // -------------------------------------------------------------------------

    /**
     * Tworzy panel z pogrubionym tytułem w obramowaniu — wariant z bezpośrednim
     * przekazaniem LayoutManagera (bez wewnętrznego wrappera).
     */
    private JPanel titledPanel(String title, java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD));
        panel.setBorder(border);
        return panel;
    }

    /**
     * Owija dowolny istniejący panel w obramowanie z tytułem.
     * Używany dla sekcji, których wewnętrzny layout jest złożony
     * (np. {@link BoxLayout}) i nie warto go odtwarzać w {@link #titledPanel}.
     */
    private JPanel wrapTitled(String title, JPanel content) {
        JPanel wrapper = new JPanel(new java.awt.BorderLayout());
        wrapper.add(content, java.awt.BorderLayout.CENTER);

        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD));
        wrapper.setBorder(border);

        return wrapper;
    }

    /**
     * Tworzy pogrubioną etykietę używaną jako nagłówek sekcji checkboxów
     * (np. „Dane meteo" nad polami temperatury/wiatru/opadów).
     */
    private JLabel boldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /**
     * Tworzy checkbox bez żadnego listenera — wyłącznie jako stan UI.
     * Wartość jest odczytywana ręcznie przez wywołujący kod (np. przycisk
     * „Zastosuj"), nie jest zapisywana automatycznie na każdy klik.
     */
    private JCheckBox plainCheckbox(String label) {
        return new JCheckBox(label, true);
    }
}