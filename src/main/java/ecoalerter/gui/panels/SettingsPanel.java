package ecoalerter.gui.panels;

import ecoalerter.config.AppConfig;
import ecoalerter.config.PersistenceMode;
import ecoalerter.model.WarningLevel;
import ecoalerter.service.DataCollectionService;
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
 * - Persystencja danych — wybór trybu (Plik / Baza danych) i formatu plikowego
 *   (JSON / CSV), zastosowanie wymaga restartu aplikacji,
 * - Zakres monitorowanych danych (DataTypeConfig — checkboxy), zastosowanie
 *   wymaga restartu aplikacji,
 * - Harmonogram — domyślny interwał odpytywania dla nowych stacji,
 *   zastosowanie jest natychmiastowe (TaskSchedulerManager czyta tę wartość
 *   z konfiguracji przy każdym planowaniu zadania, nie tylko przy starcie),
 * - Konfiguracja API (timeout, liczba ponowień) — zastosowanie natychmiastowe,
 * - Minimalny poziom wyświetlanych ostrzeżeń — zastosowanie natychmiastowe,
 * - Poziom logowania aplikacji — zastosowanie natychmiastowe przez AppLogger,
 * - Czyszczenie starych danych historycznych.
 *
 * Ustawienia oznaczone jako wymagające restartu (persystencja, zakres danych)
 * używają wspólnego wzorca: kliknięcie "Zastosuj" pyta o potwierdzenie restartu;
 * odpowiedź "Tak" zapisuje zmiany i zamyka aplikację (przez zarejestrowaną
 * akcję restartu), odpowiedź "Nie" przywraca poprzednie wartości w kontrolkach
 * bez zapisywania niczego do konfiguracji.
 *
 * Wszystkie pozostałe zmiany są stosowane natychmiast w pamięci przez
 * AppConfig.setRaw() i obowiązują do końca sesji. Trwały zapis do
 * app.properties wymaga edycji pliku poza aplikacją.
 */
public class SettingsPanel extends JPanel {
	private static final long serialVersionUID = -7830820212493657572L;

	private static final Logger log = AppLogger.get(SettingsPanel.class);

    private final AppConfig              config;
    private final DataCollectionService  dataCollectionService;

    // --- Persystencja ---
    private JRadioButton fileModeRadio;
    private JRadioButton dbModeRadio;
    private JRadioButton jsonFormatRadio;
    private JRadioButton csvFormatRadio;

    // --- Zakres monitorowanych danych ---
    private JCheckBox meteoEnabledBox;
    private JCheckBox hydroEnabledBox;
    private JCheckBox temperatureBox;
    private JCheckBox windBox;
    private JCheckBox precipitationBox;
    private JCheckBox waterLevelBox;
    private JCheckBox waterTemperatureBox;

    // --- Harmonogram ---
    private JSpinner defaultIntervalSpinner;

    // --- Pozostałe ---
    private JCheckBox               warningsEnabledBox;
    private JSpinner                apiTimeoutSpinner;
    private JSpinner                apiRetrySpinner;
    private JComboBox<WarningLevel> warningLevelCombo;
    private JComboBox<String>       logLevelCombo;
    private JSpinner                cleanupDaysSpinner;

    /** Akcja wywoływana po potwierdzeniu restartu — zwykle zamyka aplikację. */
    private Runnable onRestartRequested;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public SettingsPanel(AppConfig config, DataCollectionService dataCollectionService) {
        this.config                = config;
        this.dataCollectionService = dataCollectionService;

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
        add(buildWarningsSection());
        add(Box.createVerticalStrut(10));
        add(buildLoggingSection());
        add(Box.createVerticalStrut(10));
        add(buildCleanupSection());
        add(Box.createVerticalGlue());

        loadCurrentValues();
    }

    /**
     * Rejestruje akcję wykonywaną po potwierdzeniu restartu aplikacji
     * (np. zamknięcie okna głównego w bezpiecznej kolejności).
     * Wywoływane przez MainWindow po skonstruowaniu panelu.
     *
     * @param action akcja restartu; null wyłącza automatyczne zamknięcie
     */
    public void setOnRestartRequested(Runnable action) {
        this.onRestartRequested = action;
    }

    // -------------------------------------------------------------------------
    // Sekcja: persystencja danych (interaktywna — wymaga restartu)
    // -------------------------------------------------------------------------

    private JPanel buildPersistenceSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        ButtonGroup modeGroup = new ButtonGroup();
        fileModeRadio = new JRadioButton("Plik");
        dbModeRadio   = new JRadioButton("Baza danych (SQLite)");
        modeGroup.add(fileModeRadio);
        modeGroup.add(dbModeRadio);
        fileModeRadio.addActionListener(e -> updateFormatRadiosEnabled());
        dbModeRadio.addActionListener(e -> updateFormatRadiosEnabled());

        ButtonGroup formatGroup = new ButtonGroup();
        jsonFormatRadio = new JRadioButton("JSON");
        csvFormatRadio  = new JRadioButton("CSV");
        formatGroup.add(jsonFormatRadio);
        formatGroup.add(csvFormatRadio);

        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        modeRow.add(new JLabel("Tryb zapisu:"));
        modeRow.add(fileModeRadio);
        modeRow.add(dbModeRadio);

        JPanel formatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        formatRow.add(new JLabel("Format pliku:"));
        formatRow.add(jsonFormatRadio);
        formatRow.add(csvFormatRadio);

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyPersistence());

        JLabel note = new JLabel("Zmiana wymaga restartu aplikacji.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(java.awt.Color.GRAY);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        buttonRow.add(applyButton);
        buttonRow.add(note);

        content.add(modeRow);
        content.add(formatRow);
        content.add(buttonRow);

        return wrapTitled("Persystencja danych", content);
    }

    private void updateFormatRadiosEnabled() {
        boolean fileSelected = fileModeRadio.isSelected();
        jsonFormatRadio.setEnabled(fileSelected);
        csvFormatRadio.setEnabled(fileSelected);
    }

    private void onApplyPersistence() {
        PersistenceMode chosenMode   = fileModeRadio.isSelected()
                ? PersistenceMode.FILE : PersistenceMode.DATABASE;
        String          chosenFormat = jsonFormatRadio.isSelected() ? "JSON" : "CSV";

        PersistenceMode currentMode   = config.getPersistenceMode();
        String          currentFormat = config.getStorageFileFormat();

        boolean unchanged = chosenMode == currentMode
                && (chosenMode != PersistenceMode.FILE || chosenFormat.equals(currentFormat));
        if (unchanged) {
            JOptionPane.showMessageDialog(this, "Brak zmian do zastosowania.",
                    "Informacja", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        confirmRestartOrRevert(
                () -> {
                    config.setRaw("persistence.mode", chosenMode.name());
                    if (chosenMode == PersistenceMode.FILE) {
                        config.setRaw("storage.file.format", chosenFormat);
                    }
                    AppLogger.logConfigChange("persistence.mode", currentMode.name(), chosenMode.name());
                },
                this::loadPersistenceRadios
        );
    }

    private void loadPersistenceRadios() {
        PersistenceMode mode = config.getPersistenceMode();
        fileModeRadio.setSelected(mode == PersistenceMode.FILE);
        dbModeRadio.setSelected(mode == PersistenceMode.DATABASE);

        String format = config.getStorageFileFormat();
        jsonFormatRadio.setSelected("JSON".equalsIgnoreCase(format));
        csvFormatRadio.setSelected("CSV".equalsIgnoreCase(format));

        updateFormatRadiosEnabled();
    }

    // -------------------------------------------------------------------------
    // Sekcja: zakres monitorowanych danych (wymaga restartu)
    // -------------------------------------------------------------------------

    private JPanel buildDataTypeSection() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 4));

        meteoEnabledBox      = plainCheckbox("Dane meteo (ogólnie)");
        hydroEnabledBox      = plainCheckbox("Dane hydro (ogólnie)");
        temperatureBox       = plainCheckbox("Temperatura");
        windBox              = plainCheckbox("Prędkość wiatru");
        precipitationBox     = plainCheckbox("Opady");
        waterLevelBox        = plainCheckbox("Stan wody");
        waterTemperatureBox  = plainCheckbox("Temperatura wody");

        grid.add(meteoEnabledBox);
        grid.add(hydroEnabledBox);
        grid.add(temperatureBox);
        grid.add(waterLevelBox);
        grid.add(windBox);
        grid.add(waterTemperatureBox);
        grid.add(precipitationBox);
        grid.add(new JLabel());

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDataTypeConfig());

        JLabel note = new JLabel("Zmiana wymaga restartu aplikacji.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(java.awt.Color.GRAY);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        buttonRow.add(applyButton);
        buttonRow.add(note);

        content.add(grid);
        content.add(buttonRow);

        return wrapTitled("Zakres monitorowanych danych", content);
    }

    private void onApplyDataTypeConfig() {
        confirmRestartOrRevert(
                () -> {
                    config.setRaw("data.meteo.enabled", String.valueOf(meteoEnabledBox.isSelected()));
                    config.setRaw("data.hydro.enabled", String.valueOf(hydroEnabledBox.isSelected()));
                    config.setRaw("data.meteo.temperature", String.valueOf(temperatureBox.isSelected()));
                    config.setRaw("data.meteo.wind", String.valueOf(windBox.isSelected()));
                    config.setRaw("data.meteo.precipitation", String.valueOf(precipitationBox.isSelected()));
                    config.setRaw("data.hydro.waterLevel", String.valueOf(waterLevelBox.isSelected()));
                    config.setRaw("data.hydro.waterTemperature", String.valueOf(waterTemperatureBox.isSelected()));
                    log.info("Zakres monitorowanych danych zaktualizowany");
                },
                this::loadDataTypeCheckboxes
        );
    }

    private void loadDataTypeCheckboxes() {
        var dataTypeConfig = config.getDataTypeConfig();
        meteoEnabledBox.setSelected(dataTypeConfig.isMeteoEnabled());
        hydroEnabledBox.setSelected(dataTypeConfig.isHydroEnabled());
        temperatureBox.setSelected(dataTypeConfig.isTemperatureEnabled());
        windBox.setSelected(dataTypeConfig.isWindEnabled());
        precipitationBox.setSelected(dataTypeConfig.isPrecipitationEnabled());
        waterLevelBox.setSelected(dataTypeConfig.isWaterLevelEnabled());
        waterTemperatureBox.setSelected(dataTypeConfig.isWaterTemperatureEnabled());
    }

    // -------------------------------------------------------------------------
    // Sekcja: harmonogram — domyślny interwał (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    private JPanel buildSchedulerSection() {
        JPanel panel = titledPanel("Harmonogram", new FlowLayout(FlowLayout.LEFT, 8, 4));

        defaultIntervalSpinner = new JSpinner(new SpinnerNumberModel(
                config.getSchedulerDefaultIntervalSeconds(), 60, 86_400, 30));

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDefaultInterval());

        panel.add(new JLabel("Domyślny interwał dla nowych stacji (s):"));
        panel.add(defaultIntervalSpinner);
        panel.add(applyButton);

        return panel;
    }

    private void onApplyDefaultInterval() {
        int    newDefault = (Integer) defaultIntervalSpinner.getValue();
        String oldValue    = config.getRaw("scheduler.default.interval.seconds");

        config.setRaw("scheduler.default.interval.seconds", String.valueOf(newDefault));
        AppLogger.logConfigChange("scheduler.default.interval.seconds", oldValue,
                String.valueOf(newDefault));

        JOptionPane.showMessageDialog(this,
                "Domyślny interwał ustawiony na " + newDefault + " s.\n" +
                "Obowiązuje od razu dla nowo planowanych stacji.",
                "Zastosowano", JOptionPane.INFORMATION_MESSAGE);
    }

    // -------------------------------------------------------------------------
    // Sekcja: konfiguracja API (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    private JPanel buildApiSection() {
        JPanel panel = titledPanel("Komunikacja z API IMGW", new GridLayout(2, 2, 8, 4));

        apiTimeoutSpinner = new JSpinner(new SpinnerNumberModel(
                config.getApiTimeoutSeconds(), 1, 120, 1));
        apiTimeoutSpinner.addChangeListener(e ->
                config.setRaw("api.timeout.seconds", apiTimeoutSpinner.getValue().toString()));

        apiRetrySpinner = new JSpinner(new SpinnerNumberModel(
                config.getApiRetryCount(), 0, 10, 1));
        apiRetrySpinner.addChangeListener(e ->
                config.setRaw("api.retry.count", apiRetrySpinner.getValue().toString()));

        panel.add(new JLabel("Timeout żądania (s):"));
        panel.add(apiTimeoutSpinner);
        panel.add(new JLabel("Liczba ponowień:"));
        panel.add(apiRetrySpinner);

        return panel;
    }

    // -------------------------------------------------------------------------
    // Sekcja: filtr ostrzeżeń (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    private JPanel buildWarningsSection() {
        JPanel panel = titledPanel("Ostrzeżenia", new GridLayout(2, 2, 8, 4));

        warningsEnabledBox = plainCheckbox("Pobieraj ostrzeżenia z IMGW");
        warningsEnabledBox.addActionListener(e ->
                config.setRaw("warnings.enabled", String.valueOf(warningsEnabledBox.isSelected())));

        warningLevelCombo = new JComboBox<>(WarningLevel.values());
        warningLevelCombo.addActionListener(e -> {
            WarningLevel selected = (WarningLevel) warningLevelCombo.getSelectedItem();
            if (selected != null) {
                config.setRaw("warnings.filter.level", selected.name());
            }
        });

        panel.add(warningsEnabledBox);
        panel.add(new JLabel());
        panel.add(new JLabel("Minimalny poziom do wyświetlenia:"));
        panel.add(warningLevelCombo);

        return panel;
    }

    // -------------------------------------------------------------------------
    // Sekcja: logowanie (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

    private JPanel buildLoggingSection() {
        JPanel panel = titledPanel("Logowanie", new GridLayout(1, 2, 8, 4));

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

        panel.add(new JLabel("Poziom logowania:"));
        panel.add(logLevelCombo);

        return panel;
    }

    // -------------------------------------------------------------------------
    // Sekcja: czyszczenie danych
    // -------------------------------------------------------------------------

    private JPanel buildCleanupSection() {
        JPanel panel = titledPanel("Czyszczenie historii danych",
                new FlowLayout(FlowLayout.LEFT, 8, 4));

        cleanupDaysSpinner = new JSpinner(new SpinnerNumberModel(90, 1, 3650, 30));
        JButton cleanupButton = new JButton("Usuń dane starsze niż...");
        cleanupButton.addActionListener(e -> onCleanupRequested());

        panel.add(new JLabel("Zachowaj dane z ostatnich (dni):"));
        panel.add(cleanupDaysSpinner);
        panel.add(cleanupButton);

        return panel;
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
    // Wczytanie wartości początkowych z konfiguracji
    // -------------------------------------------------------------------------

    private void loadCurrentValues() {
        loadPersistenceRadios();
        loadDataTypeCheckboxes();
        warningsEnabledBox.setSelected(config.isWarningsEnabled());
        warningLevelCombo.setSelectedItem(config.getWarningsFilterLevel());
        logLevelCombo.setSelectedItem(config.getLogLevel());
    }

    // -------------------------------------------------------------------------
    // Wspólny wzorzec: zmiana wymagająca restartu
    // -------------------------------------------------------------------------

    /**
     * Pyta użytkownika o potwierdzenie, że zmiana wymaga restartu aplikacji.
     *
     * Po "Tak": wykonuje applyAction (zapis do AppConfig) i wywołuje
     * zarejestrowaną akcję restartu (zamknięcie aplikacji w bezpiecznej
     * kolejności) — użytkownik musi uruchomić aplikację ponownie ręcznie.
     *
     * Po "Nie": wykonuje revertAction, które przywraca poprzedni stan
     * kontrolek GUI bez zapisywania niczego do konfiguracji.
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
    // Metody pomocnicze
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

    /**
     * Tworzy checkbox bez żadnego listenera — wyłącznie jako stan UI.
     * Wartość jest odczytywana ręcznie przez wywołujący kod (np. przycisk
     * "Zastosuj"), nie jest zapisywana automatycznie na każdy klik.
     */
    private JCheckBox plainCheckbox(String label) {
        return new JCheckBox(label, true);
    }
}