package ecoalerter.gui.panels;

import ecoalerter.config.AppConfig;
import ecoalerter.gui.components.TableSortUtil;
import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.service.DataCollectionService;
import ecoalerter.service.NotificationService;
import ecoalerter.service.StationService;
import ecoalerter.util.AppLogger;
import ecoalerter.util.DateTimeUtil;
import org.apache.logging.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.ItemEvent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Panel podglądu historii danych pomiarowych dla wybranej stacji.
 *
 * Zakres czasowy wybierany slajderem — od 1h do 12 miesięcy. Tabela danych
 * jest budowana dynamicznie, kolumny różnią się w zależności od typu stacji
 * (METEO vs HYDRO) i bieżącego DataTypeConfig.
 *
 * <h2>Dwie kolumny dat</h2>
 * Tabela pokazuje obok siebie:
 * <ol>
 *   <li><b>Data i godzina pobrania</b> — czas systemowy z momentu zapisu
 *       rekordu w aplikacji (pole {@code fetchedAt}). Pokazuje KIEDY MY
 *       pobraliśmy dane.</li>
 *   <li><b>Data i godzina pomiaru</b> — czas pomiaru po stronie IMGW
 *       (pole {@code timestamp}). Pokazuje KIEDY stacja zarejestrowała pomiar.</li>
 * </ol>
 * Różnica między oboma pokazuje opóźnienie między pomiarem a jego dotarciem
 * do aplikacji.
 *
 * Panel odświeża się automatycznie po wybraniu innej stacji, przesunięciu
 * slajdera zakresu, otrzymaniu DATA_UPDATED dla wybranej stacji oraz po
 * wywołaniu {@link #refreshCurrentView()} z zewnątrz (np. SettingsPanel
 * po zmianie zakresu monitorowanych danych).
 */
public class DataViewPanel extends JPanel implements NotificationService.AppEventListener {
    private static final long serialVersionUID = 6967853359600831806L;

    private static final Logger log = AppLogger.get(DataViewPanel.class);

    /**
     * Dyskretne kroki slajdera zakresu czasowego, w godzinach.
     * 1h, 6h, 24h, 7d, 14d, 30d, 3 mies (90d), 6 mies (180d), 12 mies (365d).
     */
    private static final int[] RANGE_HOURS = {1, 6, 24, 168, 336, 720, 2160, 4320, 8760};

    /** Etykiety odpowiadające RANGE_HOURS, pokazywane przy slajderze. */
    private static final String[] RANGE_LABELS = {
            "1h", "6h", "24h", "7d", "14d", "30d", "3 mies", "6 mies", "12 mies"
    };

    /** Domyślna pozycja slajdera — indeks odpowiadający 24h. */
    private static final int DEFAULT_RANGE_INDEX = 2;

    private static final int BUTTON_GAP = 16;

    private static final String RANGE_PREF_KEY = "gui.dataview.range.hours";

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private final StationService         stationService;
    private final DataCollectionService  dataCollectionService;
    private final NotificationService    notificationService;
    private final AppConfig              config;

    private final JComboBox<Station> stationCombo;
    private final JSlider            rangeSlider;
    private final JButton            refreshButton;
    private final JLabel             summaryLabel;
    private final JTable             dataTable;
    private final DefaultTableModel  tableModel;
    private final JPanel             centerPanel;

    private volatile boolean suppressComboEvents = false;

    public DataViewPanel(StationService stationService,
                         DataCollectionService dataCollectionService,
                         NotificationService notificationService,
                         AppConfig config) {
        super(new BorderLayout());

        this.stationService        = stationService;
        this.dataCollectionService = dataCollectionService;
        this.notificationService   = notificationService;
        this.config                = config;

        this.stationCombo    = new JComboBox<>();
        this.rangeSlider     = buildRangeSlider(loadSavedRangeIndex());
        this.refreshButton   = new JButton("Odśwież");
        this.summaryLabel    = new JLabel(" ");
        this.tableModel      = new DefaultTableModel(
                new String[]{"Data i godzina pobrania", "Data i godzina pomiaru"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        this.dataTable       = new JTable(tableModel);

        stationCombo.setRenderer(new StationComboRenderer());
        stationCombo.addItemListener(this::onStationSelectionChanged);

        rangeSlider.addChangeListener(e -> {
            if (!rangeSlider.getValueIsAdjusting()) {
                saveRangeIndex(rangeSlider.getValue());
                loadData();
            }
        });

        refreshButton.addActionListener(e -> loadData());

        this.centerPanel = new JPanel(new CardLayout());
        centerPanel.add(new JScrollPane(dataTable), CARD_TABLE);
        centerPanel.add(buildEmptyStatePanel(), CARD_EMPTY);

        add(buildTopPanel(), BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(buildSummaryPanel(), BorderLayout.SOUTH);

        showEmptyState();
        notificationService.addListener(this);
        reloadStationList();
    }

    /**
     * Odczytuje ostatnio wybrany zakres (w godzinach) z konfiguracji i zwraca
     * odpowiadający mu indeks w {@link #RANGE_HOURS}. Gdy nic nie było jeszcze
     * zapisane, albo zapisana wartość nie odpowiada żadnemu krokowi slajdera
     * (np. po zmianie listy dostępnych zakresów), wraca do
     * {@link #DEFAULT_RANGE_INDEX}.
     */
    private int loadSavedRangeIndex() {
        String raw = config.getRaw(RANGE_PREF_KEY);
        if (!raw.isBlank()) {
            try {
                int savedHours = Integer.parseInt(raw.trim());
                for (int i = 0; i < RANGE_HOURS.length; i++) {
                    if (RANGE_HOURS[i] == savedHours) return i;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_RANGE_INDEX;
    }

    private void saveRangeIndex(int sliderIndex) {
        config.setRaw(RANGE_PREF_KEY, String.valueOf(RANGE_HOURS[sliderIndex]));
    }

    /** Buduje slider zakresu czasowego z etykietami z {@link #RANGE_LABELS}. */
    private JSlider buildRangeSlider(int initialIndex) {
        JSlider slider = new JSlider(0, RANGE_HOURS.length - 1, initialIndex);
        slider.setSnapToTicks(true);
        slider.setMajorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        // Szerszy niż poprzednio bo mamy 9 pozycji z dłuższymi etykietami
        // typu "12 mies" — przy 360px „3 mies" i „6 mies" zachodzą na siebie.
        slider.setPreferredSize(new Dimension(560, 50));

        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        for (int i = 0; i < RANGE_LABELS.length; i++) {
            labels.put(i, new JLabel(RANGE_LABELS[i]));
        }
        slider.setLabelTable(labels);

        return slider;
    }

    private JPanel buildTopPanel() {
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 6));
        toolBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        toolBar.add(new JLabel("Stacja: "));
        toolBar.add(stationCombo);
        toolBar.add(refreshButton);

        JPanel rangePanel = new JPanel(new BorderLayout(8, 0));
        rangePanel.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        rangePanel.add(new JLabel("Zakres:"), BorderLayout.WEST);
        rangePanel.add(rangeSlider, BorderLayout.CENTER);

        topPanel.add(toolBar);
        topPanel.add(rangePanel);
        return topPanel;
    }

    private JPanel buildSummaryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        panel.add(summaryLabel, BorderLayout.WEST);
        return panel;
    }

    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        JLabel label = new JLabel(
                "Brak dodanych stacji. Dodaj stację w zakładce \u201eStacje\u201d, aby zobaczyć tutaj dane.");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(Color.GRAY);
        panel.add(label);
        return panel;
    }

    private void showEmptyState() {
        tableModel.setRowCount(0);
        summaryLabel.setText(" ");
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_EMPTY);
    }

    private void showTableState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_TABLE);
    }

    @Override
    public void onEvent(NotificationService.AppEvent event) {
        switch (event.getType()) {
            case STATIONS_CHANGED -> reloadStationList();
            case DATA_UPDATED     -> reloadIfSelectedStation(event);
            default -> { /* nie dotyczy tego panelu */ }
        }
    }

    private void reloadIfSelectedStation(NotificationService.AppEvent event) {
        if (!(event.getPayload() instanceof Station updated)) return;

        Station selected = (Station) stationCombo.getSelectedItem();
        if (selected != null
                && selected.getId().equals(updated.getId())
                && selected.getType() == updated.getType()) {
            log.debug("Nowe dane dla wybranej stacji {} — automatyczne odświeżenie widoku",
                    updated.getId());
            loadData();
        }
    }

    public void dispose() {
        notificationService.removeListener(this);
    }

    /**
     * Wymusza przeładowanie aktualnie wyświetlanych danych. Wywoływane przez
     * SettingsPanel po zmianie zakresu monitorowanych danych albo po
     * wyczyszczeniu całej historii.
     */
    public void refreshCurrentView() {
        if (stationCombo.getSelectedItem() instanceof Station) {
            loadData();
        } else {
            tableModel.setRowCount(0);
            summaryLabel.setText(" ");
        }
    }

    /**
     * Przeładowuje listę stacji w combo boxie.
     */
    public void reloadStationList() {
        new SwingWorker<List<Station>, Void>() {
            @Override
            protected List<Station> doInBackground() throws Exception {
                return stationService.getAllStations();
            }

            @Override
            protected void done() {
                suppressComboEvents = true;
                try {
                    Station previouslySelected = (Station) stationCombo.getSelectedItem();
                    stationCombo.removeAllItems();

                    List<Station> stations = get();

                    if (stations.isEmpty()) {
                        stationCombo.addItem(null);
                        stationCombo.setEnabled(false);
                    } else {
                        stationCombo.setEnabled(true);
                        for (Station s : stations) {
                            stationCombo.addItem(s);
                        }
                        restoreSelection(previouslySelected);
                    }

                    Station nowSelected = (Station) stationCombo.getSelectedItem();
                    if (!isSameStation(previouslySelected, nowSelected)) {
                        if (nowSelected == null) {
                            showEmptyState();
                        } else {
                            loadData();
                        }
                    }
                } catch (Exception e) {
                    log.error("Nie udało się wczytać listy stacji do podglądu danych: {}",
                            e.getMessage());
                } finally {
                    suppressComboEvents = false;
                }
            }
        }.execute();
    }

    private void restoreSelection(Station previous) {
        if (previous == null) return;
        for (int i = 0; i < stationCombo.getItemCount(); i++) {
            Station item = stationCombo.getItemAt(i);
            if (item != null
                    && item.getId().equals(previous.getId())
                    && item.getType() == previous.getType()) {
                stationCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private boolean isSameStation(Station a, Station b) {
        if (a == null || b == null) return a == b;
        return a.getId().equals(b.getId()) && a.getType() == b.getType();
    }

    private void onStationSelectionChanged(ItemEvent e) {
        if (suppressComboEvents) return;
        if (e.getStateChange() == ItemEvent.SELECTED) {
            loadData();
        }
    }

    private void loadData() {
        Station station = (Station) stationCombo.getSelectedItem();
        if (station == null) {
            return;
        }

        int           rangeHours = RANGE_HOURS[rangeSlider.getValue()];
        LocalDateTime to         = LocalDateTime.now();
        LocalDateTime from       = to.minusHours(rangeHours);

        refreshButton.setEnabled(false);
        summaryLabel.setText("Wczytywanie...");

        if (station.getType() == StationType.METEO) {
            loadMeteoData(station, from, to);
        } else {
            loadHydroData(station, from, to);
        }
    }

    private void loadMeteoData(Station station, LocalDateTime from, LocalDateTime to) {
        new SwingWorker<List<MeteoData>, Void>() {
            @Override
            protected List<MeteoData> doInBackground() throws Exception {
                return dataCollectionService.getMeteoHistory(station.getId(), from, to);
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                try {
                    displayMeteoData(get());
                } catch (Exception e) {
                    handleLoadError(e);
                }
            }
        }.execute();
    }

    private void loadHydroData(Station station, LocalDateTime from, LocalDateTime to) {
        new SwingWorker<List<HydroData>, Void>() {
            @Override
            protected List<HydroData> doInBackground() throws Exception {
                return dataCollectionService.getHydroHistory(station.getId(), from, to);
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                try {
                    displayHydroData(get());
                } catch (Exception e) {
                    handleLoadError(e);
                }
            }
        }.execute();
    }

    /**
     * Buduje kolumny tabeli i wypełnia ją pomiarami meteo.
     *
     * Układ kolumn:
     *   [0] Data i godzina pobrania (fetchedAt)
     *   [1] Data i godzina pomiaru  (timestamp)
     *   [2..N] kolumny liczbowe wg DataTypeConfig
     */
    private void displayMeteoData(List<MeteoData> data) {
        showTableState();

        var dtConfig = config.getDataTypeConfig();

        List<String> columns = new ArrayList<>();
        columns.add("Data i godzina pobrania");
        columns.add("Data i godzina pomiaru");

        int numericCount = 0;
        if (dtConfig.isTemperatureEnabled())   { columns.add("Temperatura (°C)"); numericCount++; }
        if (dtConfig.isWindEnabled())          { columns.add("Wiatr (m/s)");      numericCount++; }
        if (dtConfig.isPrecipitationEnabled()) { columns.add("Opady (mm)");       numericCount++; }

        // 2 kolumny dat, potem numericCount kolumn liczbowych
        setupColumns(columns.toArray(new String[0]), 2, 2, numericCount);
        tableModel.setRowCount(0);

        for (MeteoData d : data) {
            List<Object> row = new ArrayList<>();
            row.add(DateTimeUtil.toDisplayString(d.getFetchedAt()));
            row.add(DateTimeUtil.toDisplayString(d.getTimestamp()));
            if (dtConfig.isTemperatureEnabled())   row.add(formatNullable(d.getTemperature()));
            if (dtConfig.isWindEnabled())          row.add(formatNullable(d.getWindSpeed()));
            if (dtConfig.isPrecipitationEnabled()) row.add(formatNullable(d.getPrecipitation()));
            tableModel.addRow(row.toArray());
        }

        summaryLabel.setText(buildDataSummary(data.size(), "meteo"));
    }

    /**
     * Buduje kolumny tabeli i wypełnia ją pomiarami hydro.
     *
     * Układ kolumn:
     *   [0] Data i godzina pobrania (fetchedAt)
     *   [1] Data i godzina pomiaru  (timestamp)
     *   [2] Rzeka (string, niesortowalna jako liczba)
     *   [3..N] kolumny liczbowe wg DataTypeConfig
     *   [N+1] Zjawiska (gdy hydroPhenomenaEnabled = true)
     */
    private void displayHydroData(List<HydroData> data) {
        showTableState();

        var dtConfig = config.getDataTypeConfig();

        List<String> columns = new ArrayList<>();
        columns.add("Data i godzina pobrania");
        columns.add("Data i godzina pomiaru");
        columns.add("Rzeka");

        int numericCount = 0;
        if (dtConfig.isWaterLevelEnabled())       { columns.add("Stan wody (cm)");  numericCount++; }
        if (dtConfig.isWaterTemperatureEnabled()) { columns.add("Temp. wody (°C)"); numericCount++; }
        columns.add("Przepływ (m³/s)"); numericCount++;

        boolean phenomenaShown = dtConfig.isHydroPhenomenaEnabled();
        if (phenomenaShown) columns.add("Zjawiska");

        // 2 daty na początku, kolumny liczbowe zaczynają się od indeksu 3
        // (po kolumnie „Rzeka" która jest stringiem).
        setupColumns(columns.toArray(new String[0]), 2, 3, numericCount);
        tableModel.setRowCount(0);

        for (HydroData d : data) {
            List<Object> row = new ArrayList<>();
            row.add(DateTimeUtil.toDisplayString(d.getFetchedAt()));
            row.add(DateTimeUtil.toDisplayString(d.getTimestamp()));
            row.add(d.getRiverName() != null && !d.getRiverName().isBlank()
                    ? d.getRiverName() : "—");
            if (dtConfig.isWaterLevelEnabled())       row.add(formatNullable(d.getWaterLevel()));
            if (dtConfig.isWaterTemperatureEnabled()) row.add(formatNullable(d.getWaterTemperature()));
            row.add(formatNullable(d.getFlow()));
            if (phenomenaShown) row.add(describePhenomena(d));
            tableModel.addRow(row.toArray());
        }

        summaryLabel.setText(buildDataSummary(data.size(), "hydro"));
    }

    private String buildDataSummary(int count, String kind) {
        if (count == 0) {
            return "Brak danych w wybranym zakresie. Jeżeli stacja została właśnie dodana " +
                   "albo aplikacja startowała offline — sprawdź połączenie z internetem " +
                   "i kliknij „Odśwież\".";
        }
        return count + " pomiarów " + kind + " w wybranym zakresie";
    }

    private String describePhenomena(HydroData d) {
        if (!d.hasIcePhenomenon() && !d.hasOvergrowthPhenomenon()) return "brak";
        StringBuilder sb = new StringBuilder();
        if (d.hasIcePhenomenon())        sb.append("lód ");
        if (d.hasOvergrowthPhenomenon()) sb.append("zarastanie");
        return sb.toString().trim();
    }

    /**
     * Konfiguruje sorter i rendery dla nowej struktury kolumn.
     *
     * Sorter musi być tworzony na nowo (nie tylko zmieniany komparator),
     * bo liczba i znaczenie kolumn różni się między meteo a hydro — stary
     * sorter mógłby mieć komparatory przypisane do złych indeksów.
     *
     * @param columns           nagłówki kolumn
     * @param dateColumns       liczba kolumn dat od indeksu 0 (komparator daty)
     * @param numericStart      indeks pierwszej kolumny liczbowej
     * @param numericCount      liczba kolejnych kolumn liczbowych od {@code numericStart}
     */
    private void setupColumns(String[] columns, int dateColumns,
                              int numericStart, int numericCount) {
        tableModel.setColumnIdentifiers(columns);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        for (int i = 0; i < dateColumns; i++) {
            sorter.setComparator(i, TableSortUtil.date());
        }
        for (int i = numericStart; i < numericStart + numericCount; i++) {
            sorter.setComparator(i, TableSortUtil.numeric());
        }
        dataTable.setRowSorter(sorter);

        // Renderer kolumn liczbowych — null pokazuje jako kursywne, szare
        // „niedostępne" zamiast pustej komórki lub myślnika. Kolumny dat
        // i string (np. „Rzeka", „Zjawiska") zostają z domyślnym rendererem.
        MissingValueRenderer renderer = new MissingValueRenderer(dataTable.getFont());
        for (int i = numericStart; i < numericStart + numericCount; i++) {
            dataTable.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
    }

    private Object formatNullable(Double value) {
        return value != null ? String.format("%.1f", value) : null;
    }

    private void handleLoadError(Exception e) {
        log.error("Błąd wczytywania historii danych: {}", e.getMessage());
        summaryLabel.setText("Błąd wczytywania danych");
        JOptionPane.showMessageDialog(this,
                "Nie udało się wczytać danych:\n" + e.getMessage(),
                "Błąd", JOptionPane.ERROR_MESSAGE);
    }

    // =========================================================================
    // Renderer combo boxa stacji
    // =========================================================================

    private static class StationComboRenderer extends javax.swing.DefaultListCellRenderer {
        private static final long serialVersionUID = 867598602349618961L;

        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            String text = (value instanceof Station s) ? s.getDisplayLabel() : "— brak stacji —";
            java.awt.Component c = super.getListCellRendererComponent(
                    list, text, index, isSelected, cellHasFocus);
            if (!(value instanceof Station) && c instanceof JLabel lbl) {
                lbl.setFont(lbl.getFont().deriveFont(Font.ITALIC));
                if (!isSelected) lbl.setForeground(Color.GRAY);
            }
            return c;
        }
    }

    // =========================================================================
    // Renderer kolumn liczbowych — pokazuje null jako „niedostępne"
    // =========================================================================

    private static class MissingValueRenderer extends javax.swing.table.DefaultTableCellRenderer {
        private static final long serialVersionUID = 1184576405711335659L;

        private final Font baseFont;
        private final Font italicFont;

        MissingValueRenderer(Font base) {
            this.baseFont   = base;
            this.italicFont = base.deriveFont(Font.ITALIC);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            java.awt.Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (value == null) {
                setText("niedostępne");
                setFont(italicFont);
                if (!isSelected) setForeground(Color.GRAY);
            } else {
                setFont(baseFont);
                if (!isSelected) setForeground(table.getForeground());
            }
            return c;
        }
    }
}