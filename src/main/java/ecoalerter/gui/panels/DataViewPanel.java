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
import javax.swing.JToolBar;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
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
 * Zakres czasowy wybierany jest slajderem z dyskretnymi krokami (1h, 3h, 6h,
 * 12h, 24h, 2d, 3d, 7d) — nie polem tekstowym. Tabela danych jest budowana
 * dynamicznie — kolumny różnią się w zależności od typu stacji (METEO vs HYDRO),
 * ponieważ oba typy danych mają inny zestaw mierzonych wielkości.
 *
 * Panel odświeża się automatycznie w trzech sytuacjach, bez potrzeby ręcznego
 * klikania przycisku "Odśwież":
 * - po wybraniu innej stacji z listy,
 * - po przesunięciu slajdera zakresu czasowego (po zwolnieniu, nie podczas
 *   przeciągania — żeby nie odpytywać repozytorium przy każdej pośredniej
 *   pozycji slajdera),
 * - po otrzymaniu zdarzenia DATA_UPDATED z NotificationService dla aktualnie
 *   wybranej stacji (scheduler pobrał nowe dane w tle).
 *
 * Implementuje NotificationService.AppEventListener — obsługuje też zdarzenie
 * STATIONS_CHANGED (dodanie/usunięcie/edycja stacji w innym panelu) odświeżając
 * listę stacji w combo boxie.
 */
public class DataViewPanel extends JPanel implements NotificationService.AppEventListener {

    private static final Logger log = AppLogger.get(DataViewPanel.class);

    /** Dyskretne kroki slajdera zakresu czasowego, w godzinach. */
    private static final int[] RANGE_HOURS = {1, 3, 6, 12, 24, 48, 72, 168, 720};

    /** Etykiety odpowiadające RANGE_HOURS, do wyświetlenia przy slajderze. */
    private static final String[] RANGE_LABELS = {"1h", "3h", "6h", "12h", "24h", "2d", "3d", "7d", "30d"};

    /** Domyślna pozycja slajdera przy pierwszym starcie — indeks odpowiadający 24h. */
    private static final int DEFAULT_RANGE_INDEX = 4;

    /** Klucz konfiguracji, pod którym zapamiętywany jest ostatnio wybrany zakres (w godzinach). */
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

    /**
     * Blokuje reakcję na zdarzenia zmiany combo boxa podczas programatycznego
     * przebudowywania jego zawartości (reloadStationList) — bez tego każde
     * dodanie elementu mogłoby wywołać niepotrzebne ładowanie danych.
     */
    private volatile boolean suppressComboEvents = false;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

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
        this.rangeSlider      = buildRangeSlider(loadSavedRangeIndex());
        this.refreshButton    = new JButton("Odśwież");
        this.summaryLabel     = new JLabel(" ");
        this.tableModel       = new DefaultTableModel(new String[]{"Czas"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        this.dataTable        = new JTable(tableModel);

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

        showEmptyState(); // stan początkowy, do momentu pierwszego reloadStationList()
        notificationService.addListener(this);
        reloadStationList();
    }

    // -------------------------------------------------------------------------
    // Zapamiętany zakres czasowy
    // -------------------------------------------------------------------------

    /**
     * Odczytuje ostatnio wybrany zakres (w godzinach) z konfiguracji i zwraca
     * odpowiadający mu indeks w RANGE_HOURS. Gdy nic nie było jeszcze zapisane,
     * albo zapisana wartość nie odpowiada żadnemu krokowi slajdera (np. po
     * zmianie listy dostępnych zakresów), wraca do DEFAULT_RANGE_INDEX.
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
                // nieprawidłowa wartość w pliku konfiguracyjnego — wracamy do domyślnej
            }
        }
        return DEFAULT_RANGE_INDEX;
    }

    /**
     * Zapisuje wybrany zakres (w godzinach, nie indeks) do konfiguracji —
     * config.setRaw() automatycznie zapisuje też na dysk, więc wybór
     * przetrwa restart aplikacji.
     */
    private void saveRangeIndex(int sliderIndex) {
        config.setRaw(RANGE_PREF_KEY, String.valueOf(RANGE_HOURS[sliderIndex]));
    }

    // -------------------------------------------------------------------------
    // Budowa UI
    // -------------------------------------------------------------------------

    private JSlider buildRangeSlider(int initialIndex) {
        JSlider slider = new JSlider(0, RANGE_HOURS.length - 1, initialIndex);
        slider.setSnapToTicks(true);
        slider.setMajorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setPreferredSize(new Dimension(360, 45));

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

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        toolBar.add(new JLabel("Stacja: "));
        toolBar.add(stationCombo);
        toolBar.addSeparator();
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

    /**
     * Budowa panelu wyświetlanego, gdy nie istnieje żadna dodana stacja —
     * zamiast pustej tabeli z nagłówkami, samo centrowane Microcopy.
     */
    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        JLabel label = new JLabel(
                "Brak dodanych stacji. Dodaj stację w zakładce \u201eStacje\u201d, aby zobaczyć tutaj dane.");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(Color.GRAY);

        panel.add(label); // GridBagLayout bez ograniczeń centruje pojedynczy komponent
        return panel;
    }

    /**
     * Przełącza widok na centrowane Microcopy i czyści zawartość tabeli/podsumowania.
     * Wywoływane gdy nie ma żadnej dodanej stacji (zero pozycji w combo boxie).
     */
    private void showEmptyState() {
        tableModel.setRowCount(0);
        summaryLabel.setText(" ");
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_EMPTY);
    }

    /**
     * Przełącza widok z powrotem na tabelę danych. Wywoływane gdy istnieje
     * przynajmniej jedna stacja i są dla niej (lub mogą być) dane do pokazania.
     */
    private void showTableState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_TABLE);
    }

    // -------------------------------------------------------------------------
    // NotificationService.AppEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onEvent(NotificationService.AppEvent event) {
        switch (event.getType()) {
            case STATIONS_CHANGED -> reloadStationList();
            case DATA_UPDATED     -> reloadIfSelectedStation(event);
            default -> { /* nie dotyczy tego panelu */ }
        }
    }

    /**
     * Odświeża widok, jeśli zdarzenie DATA_UPDATED dotyczy aktualnie wybranej
     * stacji. NotificationService dostarcza to zdarzenie na EDT, więc bezpiecznie
     * można od razu wywołać loadData() (sama operacja I/O i tak idzie w tło
     * przez SwingWorker).
     */
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

    /**
     * Wyrejestrowuje panel z NotificationService — wywołać przy zamykaniu zakładki/aplikacji.
     */
    public void dispose() {
        notificationService.removeListener(this);
    }

    // -------------------------------------------------------------------------
    // Ładowanie listy stacji do combo
    // -------------------------------------------------------------------------

    /**
     * Przeładowuje listę stacji w combo boxie. Wywołać po dodaniu/usunięciu stacji
     * w innym panelu, żeby combo było zsynchronizowane.
     *
     * Po przebudowaniu porównuje wybór sprzed i po — jeśli się różni (np. wybrana
     * stacja została usunięta i combo automatycznie wybrało inną, albo nie ma już
     * żadnej stacji), tabela jest odpowiednio przeładowana albo wyczyszczona.
     * Bez tego po usunięciu wybranej stacji tabela pokazywałaby nieaktualne dane
     * starej stacji, mimo że combo box już wskazuje na inną (lub na nic).
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
                    for (Station s : get()) {
                        stationCombo.addItem(s);
                    }
                    restoreSelection(previouslySelected);

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
            if (item.getId().equals(previous.getId()) && item.getType() == previous.getType()) {
                stationCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    /**
     * Porównuje dwie stacje po identyfikatorze i typie (nie po referencji obiektu) —
     * ta sama stacja po edycji nazwy/interwału wciąż liczy się jako "ta sama".
     */
    private boolean isSameStation(Station a, Station b) {
        if (a == null || b == null) return a == b;
        return a.getId().equals(b.getId()) && a.getType() == b.getType();
    }

    /**
     * Wywoływane przy zmianie wyboru w combo boxie stacji — automatycznie
     * przeładowuje dane dla nowo wybranej stacji, bez potrzeby klikania "Odśwież".
     * Ignorowane podczas programatycznego przebudowywania listy (suppressComboEvents).
     */
    private void onStationSelectionChanged(ItemEvent e) {
        if (suppressComboEvents) return;
        if (e.getStateChange() == ItemEvent.SELECTED) {
            loadData();
        }
    }

    // -------------------------------------------------------------------------
    // Ładowanie danych historycznych
    // -------------------------------------------------------------------------

    private void loadData() {
        Station station = (Station) stationCombo.getSelectedItem();
        if (station == null) {
            return; // brak stacji do wyświetlenia — nic do zrobienia, np. pusta lista na starcie
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

    // -------------------------------------------------------------------------
    // Wypełnianie tabeli
    // -------------------------------------------------------------------------

    private void displayMeteoData(List<MeteoData> data) {
        showTableState();

        var dtConfig = dataCollectionService.getDataTypeConfig();

        // Kolumna "Czas" jest zawsze widoczna. Pozostałe kolumny pojawiają się
        // tylko jeśli dane pole jest włączone w Ustawieniach — wyłączone pole
        // nigdy nie ma wartości w zapisanych danych, więc kolumna z samymi "—"
        // byłaby myląca.
        List<String> columns = new ArrayList<>();
        columns.add("Czas");
        int numericCount = 0;
        if (dtConfig.isTemperatureEnabled())   { columns.add("Temperatura (°C)"); numericCount++; }
        if (dtConfig.isWindEnabled())          { columns.add("Wiatr (m/s)");      numericCount++; }
        if (dtConfig.isPrecipitationEnabled()) { columns.add("Opady (mm)");       numericCount++; }

        setColumns(columns.toArray(new String[0]), numericCount);
        tableModel.setRowCount(0);

        for (MeteoData d : data) {
            List<Object> row = new ArrayList<>();
            row.add(DateTimeUtil.toDisplayString(d.getTimestamp()));
            if (dtConfig.isTemperatureEnabled())   row.add(formatNullable(d.getTemperature()));
            if (dtConfig.isWindEnabled())          row.add(formatNullable(d.getWindSpeed()));
            if (dtConfig.isPrecipitationEnabled()) row.add(formatNullable(d.getPrecipitation()));
            tableModel.addRow(row.toArray());
        }

        summaryLabel.setText(data.size() + " pomiarów meteo w wybranym zakresie");
    }

    private void displayHydroData(List<HydroData> data) {
        showTableState();

        var dtConfig = dataCollectionService.getDataTypeConfig();

        // "Przepływ" i "Zjawiska" nie mają przełącznika w Ustawieniach —
        // są zawsze zbierane, więc zawsze widoczne, niezależnie od konfiguracji.
        List<String> columns = new ArrayList<>();
        columns.add("Czas");
        int numericCount = 0;
        if (dtConfig.isWaterLevelEnabled())       { columns.add("Stan wody (cm)");  numericCount++; }
        if (dtConfig.isWaterTemperatureEnabled()) { columns.add("Temp. wody (°C)"); numericCount++; }
        columns.add("Przepływ (m³/s)"); numericCount++;
        columns.add("Zjawiska");

        setColumns(columns.toArray(new String[0]), numericCount);
        tableModel.setRowCount(0);

        for (HydroData d : data) {
            List<Object> row = new ArrayList<>();
            row.add(DateTimeUtil.toDisplayString(d.getTimestamp()));
            if (dtConfig.isWaterLevelEnabled())       row.add(formatNullable(d.getWaterLevel()));
            if (dtConfig.isWaterTemperatureEnabled()) row.add(formatNullable(d.getWaterTemperature()));
            row.add(formatNullable(d.getFlow()));
            row.add(describePhenomena(d));
            tableModel.addRow(row.toArray());
        }

        summaryLabel.setText(data.size() + " pomiarów hydro w wybranym zakresie");
    }

    private String describePhenomena(HydroData d) {
        if (!d.hasIcePhenomenon() && !d.hasOvergrowthPhenomenon()) return "—";
        StringBuilder sb = new StringBuilder();
        if (d.hasIcePhenomenon())        sb.append("lód ");
        if (d.hasOvergrowthPhenomenon())  sb.append("zarastanie");
        return sb.toString().trim();
    }

    /**
     * Ustawia kolumny tabeli i odbudowuje sorter dla nowej struktury kolumn.
     * Sorter musi być tworzony na nowo (nie tylko zmieniany komparator),
     * bo liczba i znaczenie kolumn różni się między meteo i hydro —
     * stary sorter mógłby mieć komparatory przypisane do złych indeksów.
     *
     * Kolumna 0 ("Czas") jest zawsze datą. Kolumny 1..numericColumnCount
     * są zawsze liczbowe. Pozostałe kolumny (jeśli są) zostają z domyślnym
     * porównaniem alfabetycznym — np. "Zjawiska" w danych hydro.
     *
     * @param columns            nagłówki kolumn do ustawienia
     * @param numericColumnCount liczba kolejnych kolumn liczbowych zaczynających się od indeksu 1
     */
    private void setColumns(String[] columns, int numericColumnCount) {
        tableModel.setColumnIdentifiers(columns);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, TableSortUtil.date());
        for (int i = 1; i <= numericColumnCount; i++) {
            sorter.setComparator(i, TableSortUtil.numeric());
        }
        dataTable.setRowSorter(sorter);
    }

    private String formatNullable(Double value) {
        return value != null ? String.format("%.1f", value) : "—";
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
        @Override
        public java.awt.Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            String text = (value instanceof Station s) ? s.getDisplayLabel() : "BRAK";
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        }
    }
}