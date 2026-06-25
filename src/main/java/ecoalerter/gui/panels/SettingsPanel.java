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
import java.util.Hashtable;

/**
 * Panel ustawień aplikacji.
 *
 * Sekcje:
 * <ul>
 *   <li><b>Persystencja danych</b> — wybór trybu (Plik / Baza). Wymaga restartu.</li>
 *   <li><b>Zakres monitorowanych danych</b> — które pola pomiarów pokazywać
 *       w widoku. Stosowane natychmiast (DataViewPanel czyta na bieżąco).</li>
 *   <li><b>Harmonogram</b> — domyślny interwał (1-60 min) dla nowo planowanych stacji.</li>
 *   <li><b>API IMGW</b> — timeout, liczba ponowień.</li>
 *   <li><b>Dziennik zdarzeń</b> — poziom logowania.</li>
 *   <li><b>Czyszczenie historii</b> — usunięcie starych pomiarów lub wszystkich danych.</li>
 *   <li><b>Reset</b> — przywrócenie ustawień fabrycznych (wymaga restartu).</li>
 * </ul>
 */
public class SettingsPanel extends JPanel {
    private static final long serialVersionUID = 2781647122217999954L;

    private static final Logger log = AppLogger.get(SettingsPanel.class);

    private static final int MIN_INTERVAL_MINUTES = 1;
    private static final int MAX_INTERVAL_MINUTES = 60;

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
    private JCheckBox hydroPhenomenaBox;

    private JSlider  defaultIntervalSlider;

    private JSpinner                apiTimeoutSpinner;
    private JSpinner                apiRetrySpinner;
    private JComboBox<String>       logLevelCombo;
    private JSpinner                cleanupDaysSpinner;

    private Runnable onRestartRequested;
    private Runnable onDataTypeConfigChanged;

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

    public void setOnRestartRequested(Runnable action) {
        this.onRestartRequested = action;
    }

    public void setOnDataTypeConfigChanged(Runnable action) {
        this.onDataTypeConfigChanged = action;
    }

    // -------------------------------------------------------------------------
    // Sekcja: persystencja danych (wymaga restartu)
    // -------------------------------------------------------------------------

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

    private void loadPersistenceRadios() {
        PersistenceMode mode = config.getPersistenceMode();
        fileModeRadio.setSelected(mode == PersistenceMode.FILE);
        dbModeRadio.setSelected(mode == PersistenceMode.DATABASE);
    }

    // -------------------------------------------------------------------------
    // Sekcja: zakres monitorowanych danych (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    /**
     * Buduje sekcję checkboxów decydujących, które pola pomiarów pokazywać w widoku.
     * Hydro ma teraz trzy przełączniki — stan wody, temperatura wody i zjawiska
     * (lód + zarastanie razem, bo wszystkie zjawiska prezentowane są w GUI
     * w jednej kolumnie „Zjawiska").
     */
    private JPanel buildDataTypeSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 4));

        temperatureBox       = plainCheckbox("Temperatura");
        windBox              = plainCheckbox("Prędkość wiatru");
        precipitationBox     = plainCheckbox("Opady");
        waterLevelBox        = plainCheckbox("Stan wody");
        waterTemperatureBox  = plainCheckbox("Temperatura wody");
        hydroPhenomenaBox    = plainCheckbox("Zjawiska (lód, zarastanie)");

        grid.add(boldLabel("Dane meteo"));
        grid.add(boldLabel("Dane hydro"));
        grid.add(temperatureBox);    grid.add(waterLevelBox);
        grid.add(windBox);           grid.add(waterTemperatureBox);
        grid.add(precipitationBox);  grid.add(hydroPhenomenaBox);

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDataTypeConfig());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        buttonRow.add(applyButton);

        content.add(grid);
        content.add(buttonRow);

        return wrapTitled("Zakres monitorowanych danych", content);
    }

    private void onApplyDataTypeConfig() {
        var current = config.getDataTypeConfig();
        boolean hasChanges =
                current.isTemperatureEnabled()      != temperatureBox.isSelected()
             || current.isWindEnabled()             != windBox.isSelected()
             || current.isPrecipitationEnabled()    != precipitationBox.isSelected()
             || current.isWaterLevelEnabled()       != waterLevelBox.isSelected()
             || current.isWaterTemperatureEnabled() != waterTemperatureBox.isSelected()
             || current.isHydroPhenomenaEnabled()   != hydroPhenomenaBox.isSelected();

        if (!hasChanges) {
            JOptionPane.showMessageDialog(this, "Brak zmian do zastosowania.",
                    "Informacja", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        config.setRaw("data.meteo.enabled", "true");
        config.setRaw("data.hydro.enabled", "true");
        config.setRaw("data.meteo.temperature",      String.valueOf(temperatureBox.isSelected()));
        config.setRaw("data.meteo.wind",             String.valueOf(windBox.isSelected()));
        config.setRaw("data.meteo.precipitation",    String.valueOf(precipitationBox.isSelected()));
        config.setRaw("data.hydro.waterLevel",       String.valueOf(waterLevelBox.isSelected()));
        config.setRaw("data.hydro.waterTemperature", String.valueOf(waterTemperatureBox.isSelected()));
        config.setRaw("data.hydro.phenomena",        String.valueOf(hydroPhenomenaBox.isSelected()));

        log.info("Zakres monitorowanych danych zaktualizowany");

        if (onDataTypeConfigChanged != null) {
            onDataTypeConfigChanged.run();
        }

        JOptionPane.showMessageDialog(this,
                "Zakres monitorowanych danych zaktualizowany.",
                "Zastosowano", JOptionPane.INFORMATION_MESSAGE);
    }

    private void loadDataTypeCheckboxes() {
        var dataTypeConfig = config.getDataTypeConfig();
        temperatureBox.setSelected(dataTypeConfig.isTemperatureEnabled());
        windBox.setSelected(dataTypeConfig.isWindEnabled());
        precipitationBox.setSelected(dataTypeConfig.isPrecipitationEnabled());
        waterLevelBox.setSelected(dataTypeConfig.isWaterLevelEnabled());
        waterTemperatureBox.setSelected(dataTypeConfig.isWaterTemperatureEnabled());
        hydroPhenomenaBox.setSelected(dataTypeConfig.isHydroPhenomenaEnabled());
    }

    // -------------------------------------------------------------------------
    // Sekcja: harmonogram — domyślny interwał (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    /**
     * Buduje sekcję z suwakiem domyślnego interwału (1-60 min) dla nowo
     * planowanych stacji. Major ticks z labelami przy 1, 5, 10, 15, ..., 60;
     * minor ticks co 1 minutę. Live readout obok suwaka pokazuje aktualną
     * wartość ("1 min", "5 min", ...).
     */
    private JPanel buildSchedulerSection() {
        JPanel content = new JPanel(new java.awt.BorderLayout(8, 0));

        int currentMinutes = clampToMinuteSlider(config.getSchedulerDefaultIntervalSeconds() / 60);
        defaultIntervalSlider = buildIntervalSlider(currentMinutes);

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDefaultInterval());

        JPanel sliderRow = new JPanel(new java.awt.BorderLayout(8, 0));
        sliderRow.add(defaultIntervalSlider, java.awt.BorderLayout.CENTER);

        content.add(new JLabel("Jak często sprawdzać nowe stacje:"), java.awt.BorderLayout.WEST);
        content.add(sliderRow, java.awt.BorderLayout.CENTER);
        content.add(applyButton, java.awt.BorderLayout.EAST);

        return wrapTitled("Harmonogram", content);
    }

    /**
     * Buduje slider interwału 1-60 min z labelami przy 1, 5, 10, ..., 60.
     * Bez własnego LabelTable JSlider z minimum=1 i majorTickSpacing=5 rysuje
     * labelki od 1 co 5 (1, 6, 11, 16, ...) — odsunięte od pełnych dziesiątek.
     */
    private JSlider buildIntervalSlider(int initialMinutes) {
        JSlider slider = new JSlider(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES, initialMinutes);
        slider.setMajorTickSpacing(5);
        slider.setMinorTickSpacing(1);
        slider.setSnapToTicks(true);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);

        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        labels.put(1, new JLabel("1"));
        for (int v = 5; v <= 60; v += 5) {
            labels.put(v, new JLabel(String.valueOf(v)));
        }
        slider.setLabelTable(labels);

        slider.setPreferredSize(new java.awt.Dimension(360, 50));
        return slider;
    }

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

    private int clampToMinuteSlider(int minutes) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
    }

    // -------------------------------------------------------------------------
    // Sekcja: konfiguracja API (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

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

    private JPanel buildCleanupSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel byAgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        cleanupDaysSpinner = new JSpinner(new SpinnerNumberModel(90, 1, 3650, 30));
        JButton cleanupOldButton = new JButton("Usuń dane starsze niż...");
        cleanupOldButton.addActionListener(e -> onCleanupRequested());
        byAgeRow.add(new JLabel("Zachowaj dane z ostatnich (dni):"));
        byAgeRow.add(cleanupDaysSpinner);
        byAgeRow.add(cleanupOldButton);

        JPanel allRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton clearAllButton = new JButton("Wyczyść wszystkie dane pomiarowe");
        clearAllButton.addActionListener(e -> onClearAllRequested());
        allRow.add(clearAllButton);

        content.add(byAgeRow);
        content.add(allRow);

        return wrapTitled("Czyszczenie historii danych", content);
    }

    private void onClearAllRequested() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Czy na pewno chcesz usunąć WSZYSTKIE stacje wraz z ich danymi\n" +
                "pomiarowymi? Tej operacji nie można cofnąć.\n\n",
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
                stationService.clearAll();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    log.info("Wszystkie stacje i dane zostały wyczyszczone");

                    notificationService.notifyStationsChanged();

                    if (onDataTypeConfigChanged != null) {
                        onDataTypeConfigChanged.run();
                    }

                    JOptionPane.showMessageDialog(SettingsPanel.this,
                            "Wszystkie stacje wraz z ich danymi zostały usunięte.",
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

    private void onResetSettings() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Reset przywróci wszystkie ustawienia do wartości domyślnych\n" +
                "i WYMAGA zamknięcia aplikacji.\n" +
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

    private void loadCurrentValues() {
        loadPersistenceRadios();
        loadDataTypeCheckboxes();
        logLevelCombo.setSelectedItem(config.getLogLevel());
        apiTimeoutSpinner.setValue(config.getApiTimeoutSeconds());
        apiRetrySpinner.setValue(config.getApiRetryCount());
        int minutes = clampToMinuteSlider(config.getSchedulerDefaultIntervalSeconds() / 60);
        defaultIntervalSlider.setValue(minutes);
    }

    // -------------------------------------------------------------------------
    // Wspólny wzorzec: zmiana wymagająca restartu
    // -------------------------------------------------------------------------

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

    private JPanel titledPanel(String title, java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD));
        panel.setBorder(border);
        return panel;
    }

    private JPanel wrapTitled(String title, JPanel content) {
        JPanel wrapper = new JPanel(new java.awt.BorderLayout());
        wrapper.add(content, java.awt.BorderLayout.CENTER);

        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD));
        wrapper.setBorder(border);

        return wrapper;
    }

    private JLabel boldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private JCheckBox plainCheckbox(String label) {
        return new JCheckBox(label, true);
    }
}