package ecoalerter.gui.panels;

import ecoalerter.config.AppConfig;
import ecoalerter.config.PersistenceMode;
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
 * - Persystencja danych — wybór trybu (Plik / Baza danych), zastosowanie
 *   wymaga restartu aplikacji,
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

    private static final Logger log = AppLogger.get(SettingsPanel.class);

    /** Zakres slidera interwału domyślnego, w minutach. */
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 30;

    private final AppConfig              config;
    private final DataCollectionService  dataCollectionService;

    // --- Persystencja ---
    private JRadioButton fileModeRadio;
    private JRadioButton dbModeRadio;

    // --- Zakres monitorowanych danych ---
    private JCheckBox temperatureBox;
    private JCheckBox windBox;
    private JCheckBox precipitationBox;
    private JCheckBox waterLevelBox;
    private JCheckBox waterTemperatureBox;

    // --- Harmonogram ---
    private JSlider  defaultIntervalSlider;

    // --- Pozostałe ---
    private JSpinner                apiTimeoutSpinner;
    private JSpinner                apiRetrySpinner;
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
    // Sekcja: zakres monitorowanych danych (wymaga restartu)
    // -------------------------------------------------------------------------

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
                    // Brak już osobnych przełączników "ogólnie" — kategoria jest
                    // efektywnie włączona, jeśli przynajmniej jedno jej pole jest
                    // zaznaczone; odznaczenie wszystkich pól danej kategorii daje
                    // ten sam efekt co dawne wyłączenie "ogólnie".
                    config.setRaw("data.meteo.enabled", "true");
                    config.setRaw("data.hydro.enabled", "true");
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
        JPanel content = new JPanel(new java.awt.BorderLayout(8, 0));

        int currentMinutes = clampToMinuteSlider(config.getSchedulerDefaultIntervalSeconds() / 60);
        defaultIntervalSlider = new JSlider(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES, currentMinutes);
        defaultIntervalSlider.setMajorTickSpacing(5);
        defaultIntervalSlider.setSnapToTicks(true);
        defaultIntervalSlider.setPaintTicks(true);
        defaultIntervalSlider.setPaintLabels(true); // minimum=5 i majorTickSpacing=5 dają etykiety 5,10,...,30 automatycznie
        defaultIntervalSlider.setPreferredSize(new java.awt.Dimension(320, 45));

        JButton applyButton = new JButton("Zastosuj");
        applyButton.addActionListener(e -> onApplyDefaultInterval());

        content.add(new JLabel("Jak często sprawdzać nowe stacje (min):"), java.awt.BorderLayout.WEST);
        content.add(defaultIntervalSlider, java.awt.BorderLayout.CENTER);
        content.add(applyButton, java.awt.BorderLayout.EAST);

        return wrapTitled("Harmonogram", content);
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

    /**
     * Ogranicza wartość w minutach do zakresu obsługiwanego przez slider
     * (1-60 min). Istniejące interwały zapisane przed wprowadzeniem slidera
     * mogły przekraczać tę godzinę — są tu po prostu przycinane do nowego limitu.
     */
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
    // Sekcja: filtr ostrzeżeń (zastosowanie natychmiastowe)
    // -------------------------------------------------------------------------

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
    // Sekcja: reset ustawień
    // -------------------------------------------------------------------------

    private JPanel buildResetSection() {
        JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));

        JButton resetButton = new JButton("Resetuj ustawienia");
        resetButton.addActionListener(e -> onResetSettings());

        JLabel note = new JLabel("Przywraca domyślne ustawienia i zamyka aplikację — wymaga ponownego uruchomienia.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(java.awt.Color.GRAY);

        content.add(resetButton);
        content.add(note);

        return wrapTitled("Reset", content);
    }

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
     * Tworzy pogrubioną etykietę używaną jako nagłówek sekcji checkboxów
     * (np. "Dane meteo" nad polami temperatury/wiatru/opadów).
     */
    private JLabel boldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
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