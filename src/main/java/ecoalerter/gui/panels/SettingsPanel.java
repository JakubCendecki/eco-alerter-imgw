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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import java.awt.Font;
import java.awt.GridLayout;

/**
 * Panel ustawień aplikacji.
 *
 * Sekcje:
 * - Informacje o trybie persystencji (tylko odczyt — zmiana wymaga restartu,
 *   bo zmienia implementację DataRepository przekazaną do całej aplikacji),
 * - Zakres monitorowanych danych (DataTypeConfig — checkboxy),
 * - Konfiguracja API (timeout, liczba ponowień),
 * - Minimalny poziom wyświetlanych ostrzeżeń,
 * - Poziom logowania aplikacji (zmiana w runtime przez AppLogger),
 * - Czyszczenie starych danych historycznych.
 *
 * Wszystkie zmiany poza czyszczeniem danych są stosowane natychmiast
 * w pamięci przez AppConfig.setRaw() i obowiązują do końca sesji.
 * Trwały zapis do app.properties wymaga edycji pliku poza aplikacją —
 * ograniczenie świadomie przyjęte, by nie nadpisywać pliku konfiguracyjnego
 * bez wyraźnej kontroli użytkownika nad plikami na dysku.
 */
public class SettingsPanel extends JPanel {
	private static final long serialVersionUID = 8819964101267957063L;

	private static final Logger log = AppLogger.get(SettingsPanel.class);

    private final AppConfig              config;
    private final DataCollectionService  dataCollectionService;

    private JCheckBox meteoEnabledBox;
    private JCheckBox hydroEnabledBox;
    private JCheckBox temperatureBox;
    private JCheckBox windBox;
    private JCheckBox precipitationBox;
    private JCheckBox pressureBox;
    private JCheckBox waterLevelBox;
    private JCheckBox waterTemperatureBox;
    private JCheckBox warningsEnabledBox;

    private JSpinner            apiTimeoutSpinner;
    private JSpinner            apiRetrySpinner;
    private JComboBox<WarningLevel> warningLevelCombo;
    private JComboBox<String>       logLevelCombo;
    private JSpinner            cleanupDaysSpinner;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public SettingsPanel(AppConfig config, DataCollectionService dataCollectionService) {
        this.config                = config;
        this.dataCollectionService = dataCollectionService;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildPersistenceInfoSection());
        add(Box.createVerticalStrut(10));
        add(buildDataTypeSection());
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

    // -------------------------------------------------------------------------
    // Sekcja: informacja o persystencji (tylko odczyt)
    // -------------------------------------------------------------------------

    private JPanel buildPersistenceInfoSection() {
        JPanel grid = new JPanel(new GridLayout(2, 2, 8, 4));

        PersistenceMode mode = config.getPersistenceMode();
        grid.add(new JLabel("Aktualny tryb:"));
        grid.add(boldLabel(mode.name()));

        grid.add(new JLabel("Szczegóły:"));
        grid.add(new JLabel(mode == PersistenceMode.FILE
                ? "Format: " + config.getStorageFileFormat() + ", katalog: " + config.getStorageFileDir()
                : "URL: " + maskUrl(config.getDbUrl())));

        JLabel note = new JLabel("Zmiana trybu persystencji wymaga restartu aplikacji.");
        note.setFont(note.getFont().deriveFont(Font.ITALIC, 11f));
        note.setForeground(java.awt.Color.GRAY);

        JPanel wrapper = new JPanel(new java.awt.BorderLayout());
        wrapper.add(grid, java.awt.BorderLayout.CENTER);
        wrapper.add(note, java.awt.BorderLayout.SOUTH);

        TitledBorder border = BorderFactory.createTitledBorder("Persystencja danych");
        border.setTitleFont(border.getTitleFont().deriveFont(Font.BOLD));
        wrapper.setBorder(border);

        return wrapper;
    }

    private String maskUrl(String url) {
        return url != null ? url.replaceAll("(?i)(password=)[^&;]+", "$1***") : "—";
    }

    // -------------------------------------------------------------------------
    // Sekcja: zakres monitorowanych danych
    // -------------------------------------------------------------------------

    private JPanel buildDataTypeSection() {
        JPanel panel = titledPanel("Zakres monitorowanych danych", new GridLayout(0, 2, 12, 4));

        meteoEnabledBox = checkbox("Dane meteo (ogólnie)",
                v -> config.setRaw("data.meteo.enabled", v));
        hydroEnabledBox = checkbox("Dane hydro (ogólnie)",
                v -> config.setRaw("data.hydro.enabled", v));

        temperatureBox   = checkbox("Temperatura", v -> config.setRaw("data.meteo.temperature", v));
        windBox          = checkbox("Prędkość wiatru", v -> config.setRaw("data.meteo.wind", v));
        precipitationBox = checkbox("Opady", v -> config.setRaw("data.meteo.precipitation", v));
        pressureBox      = checkbox("Ciśnienie", v -> config.setRaw("data.meteo.pressure", v));

        waterLevelBox       = checkbox("Stan wody", v -> config.setRaw("data.hydro.waterLevel", v));
        waterTemperatureBox = checkbox("Temperatura wody", v -> config.setRaw("data.hydro.waterTemperature", v));

        panel.add(meteoEnabledBox);
        panel.add(hydroEnabledBox);
        panel.add(temperatureBox);
        panel.add(waterLevelBox);
        panel.add(windBox);
        panel.add(waterTemperatureBox);
        panel.add(precipitationBox);
        panel.add(new JLabel());
        panel.add(pressureBox);
        panel.add(new JLabel());

        return panel;
    }

    // -------------------------------------------------------------------------
    // Sekcja: konfiguracja API
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
    // Sekcja: filtr ostrzeżeń
    // -------------------------------------------------------------------------

    private JPanel buildWarningsSection() {
        JPanel panel = titledPanel("Ostrzeżenia", new GridLayout(2, 2, 8, 4));

        warningsEnabledBox = checkbox("Pobieraj ostrzeżenia z IMGW",
                v -> config.setRaw("warnings.enabled", v));

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
    // Sekcja: logowanie
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
        JPanel panel = titledPanel("Czyszczenie historii danych", new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 4));

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
        var dataTypeConfig = config.getDataTypeConfig();

        meteoEnabledBox.setSelected(dataTypeConfig.isMeteoEnabled());
        hydroEnabledBox.setSelected(dataTypeConfig.isHydroEnabled());
        temperatureBox.setSelected(dataTypeConfig.isTemperatureEnabled());
        windBox.setSelected(dataTypeConfig.isWindEnabled());
        precipitationBox.setSelected(dataTypeConfig.isPrecipitationEnabled());
        pressureBox.setSelected(dataTypeConfig.isPressureEnabled());
        waterLevelBox.setSelected(dataTypeConfig.isWaterLevelEnabled());
        waterTemperatureBox.setSelected(dataTypeConfig.isWaterTemperatureEnabled());
        warningsEnabledBox.setSelected(config.isWarningsEnabled());
        warningLevelCombo.setSelectedItem(config.getWarningsFilterLevel());
        logLevelCombo.setSelectedItem(config.getLogLevel());
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

    private JLabel boldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /**
     * Tworzy checkbox z listenerem konwertującym stan na string "true"/"false"
     * i przekazującym go do podanego konsumenta (zwykle config.setRaw).
     */
    private JCheckBox checkbox(String label, java.util.function.Consumer<String> onChange) {
        JCheckBox box = new JCheckBox(label, true);
        box.addActionListener(e -> onChange.accept(String.valueOf(box.isSelected())));
        return box;
    }
}