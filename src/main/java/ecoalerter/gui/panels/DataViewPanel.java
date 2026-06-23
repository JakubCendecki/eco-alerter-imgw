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
 * Zakres czasowy wybierany jest slajderem z dyskretnymi krokami (1h, 3h, 6h,
 * 12h, 24h, 2d, 3d, 7d, 30d) — nie polem tekstowym. Tabela danych jest budowana
 * dynamicznie — kolumny różnią się w zależności od typu stacji (METEO vs HYDRO),
 * ponieważ oba typy danych mają inny zestaw mierzonych wielkości.
 *
 * Panel odświeża się automatycznie:
 * <ul>
 *   <li>po wybraniu innej stacji z listy,</li>
 *   <li>po przesunięciu slajdera zakresu czasowego (po zwolnieniu),</li>
 *   <li>po otrzymaniu DATA_UPDATED z NotificationService dla aktualnie wybranej stacji,</li>
 *   <li>po wywołaniu {@link #refreshCurrentView()} z zewnątrz (np. SettingsPanel
 *       po zmianie zakresu monitorowanych danych).</li>
 * </ul>
 *
 * DataTypeConfig jest czytany z AppConfig na bieżąco przy każdym
 * {@code displayMeteoData}/{@code displayHydroData} — dzięki temu zmiana
 * checkboxów w Ustawieniach od razu odbija się w widoku, bez restartu.
 */
public class DataViewPanel extends JPanel implements NotificationService.AppEventListener {

    private static final Logger log = AppLogger.get(DataViewPanel.class);

    /** Dyskretne kroki slajdera zakresu czasowego, w godzinach. */
    private static final int[] RANGE_HOURS = {1, 3, 6, 12, 24, 48, 72, 168, 720};

    /** Etykiety odpowiadające RANGE_HOURS, pokazywane przy slajderze. */
    private static final String[] RANGE_LABELS = {"1h", "3h", "6h", "12h", "24h", "2d", "3d", "7d", "30d"};

    /** Domyślna pozycja slajdera przy pierwszym starcie — indeks odpowiadający 24h. */
    private static final int DEFAULT_RANGE_INDEX = 4;

    /** Odstęp między kontrolkami w poziomym rzędzie akcji (px). */
    private static final int BUTTON_GAP = 16;

    /** Klucz konfiguracji, pod którym zapamiętywany jest wybrany zakres (w godzinach). */
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

    /**
     * @param stationService        serwis stacji (do listy stacji w combo)
     * @param dataCollectionService serwis danych (do odczytu historii)
     * @param notificationService   szyna zdarzeń aplikacji
     * @param config                konfiguracja — czytana na bieżąco
     *                              przy każdym renderze tabeli
     */
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
        this.tableModel       = new DefaultTableModel(new String[]{"Data i godzina pomiaru"}, 0) {
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

    /**
     * Zapisuje wybrany zakres (w godzinach, nie indeks) do konfiguracji.
     * {@code AppConfig.setRaw} automatycznie zapisuje na dysk, więc wybór
     * przetrwa restart aplikacji.
     */
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
        slider.setPreferredSize(new Dimension(360, 45));

        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        for (int i = 0; i < RANGE_LABELS.length; i++) {
            labels.put(i, new JLabel(RANGE_LABELS[i]));
        }
        slider.setLabelTable(labels);

        return slider;
    }

    /** Buduje panel górny z wyborem stacji, przyciskiem Odśwież i slajderem zakresu. */
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

    /** Buduje pasek pod tabelą z opisem liczby pomiarów lub komunikatem o braku danych. */
    private JPanel buildSummaryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        panel.add(summaryLabel, BorderLayout.WEST);
        return panel;
    }

    /**
     * Budowa panelu wyświetlanego, gdy nie istnieje żadna dodana stacja —
     * zamiast pustej tabeli z nagłówkami, samo centrowane Microcopy
     * kierujące użytkownika do zakładki „Stacje".
     */
    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        JLabel label = new JLabel(
                "Brak dodanych stacji. Dodaj stację w zakładce \u201eStacje\u201d, aby zobaczyć tutaj dane.");
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(Color.GRAY);
        panel.add(label);
        return panel;
    }

    /**
     * Przełącza widok na centrowane Microcopy i czyści zawartość tabeli/podsumowania.
     * Wywoływane gdy nie ma żadnej dodanej stacji.
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

    /**
     * Reaguje na zdarzenia z {@link NotificationService}:
     * STATIONS_CHANGED — przeładowuje listę stacji w combo;
     * DATA_UPDATED — odświeża tabelę jeśli zdarzenie dotyczy wybranej stacji.
     */
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
     * Wymusza przeładowanie aktualnie wyświetlanych danych — kolumny tabeli
     * zostaną przebudowane na podstawie świeżego DataTypeConfig, a wiersze
     * przeładowane z repozytorium. Wywoływane przez SettingsPanel po zmianie
     * zakresu monitorowanych danych albo po wyczyszczeniu całej historii.
     *
     * Bezpieczne do wywołania niezależnie od stanu panelu — gdy nie ma
     * wybranej stacji, loadData() po prostu wraca bez efektu.
     */
    public void refreshCurrentView() {
        if (stationCombo.getSelectedItem() instanceof Station) {
            loadData();
        } else {
            // Po wyczyszczeniu danych może nie być selekcji — zerujemy też
            // ewentualnie wiszącą zawartość tabeli z poprzedniej sesji.
            tableModel.setRowCount(0);
            summaryLabel.setText(" ");
        }
    }

    /**
     * Przeładowuje listę stacji w combo boxie. Wywoływać po dodaniu/usunięciu
     * stacji w innym panelu, żeby combo było zsynchronizowane.
     *
     * Po przebudowaniu porównuje wybór sprzed i po — jeśli się różni (np.
     * wybrana stacja została usunięta i combo automatycznie wybrało inną,
     * albo nie ma już żadnej stacji), tabela jest odpowiednio przeładowana
     * lub wyczyszczona. Bez tego po usunięciu wybranej stacji tabela
     * pokazywałaby nieaktualne dane.
     *
     * Gdy lista stacji jest pusta, do combo trafia null jako placeholder
     * (renderowany jako „— brak stacji —" przez {@link StationComboRenderer})
     * i combo jest wyłączane — bez tego Swing rysuje pusty JComboBox jako
     * „..".
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
                        // Bez tego placeholdera Swing rysuje pusty JComboBox jako
                        // wąskie pole z „.." z domyślnego L&F. Dodanie sentinel-itemu
                        // null i wyłączenie combo daje czytelny tekst „— brak stacji —"
                        // przez StationComboRenderer.
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

    /**
     * Przywraca wybór po przebudowaniu listy. Szuka stacji o tym samym
     * ID i typie co poprzednio wybrana — jeśli wciąż istnieje, ustawia ją
     * jako wybraną. Jeśli nie istnieje, combo wybiera pierwszy element
     * (domyślne zachowanie JComboBox).
     */
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

    /**
     * Porównuje dwie stacje po identyfikatorze i typie (nie po referencji obiektu) —
     * ta sama stacja po edycji nazwy/interwału wciąż liczy się jako „ta sama".
     *
     * @return true gdy obie są null, lub gdy mają identyczne ID + typ
     */
    private boolean isSameStation(Station a, Station b) {
        if (a == null || b == null) return a == b;
        return a.getId().equals(b.getId()) && a.getType() == b.getType();
    }

    /**
     * Wywoływane przy zmianie wyboru w combo boxie stacji — automatycznie
     * przeładowuje dane dla nowo wybranej stacji, bez potrzeby klikania
     * „Odśwież". Ignorowane podczas programatycznego przebudowywania listy
     * ({@link #suppressComboEvents} == true).
     */
    private void onStationSelectionChanged(ItemEvent e) {
        if (suppressComboEvents) return;
        if (e.getStateChange() == ItemEvent.SELECTED) {
            loadData();
        }
    }

    /**
     * Główna metoda ładowania danych dla aktualnie wybranej stacji.
     * Wybiera ścieżkę meteo lub hydro w zależności od typu, oblicza zakres
     * czasowy ze slajdera i odpala odpowiedni SwingWorker.
     * Brak operacji gdy żadna stacja nie jest wybrana.
     */
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

    /** Asynchronicznie ładuje pomiary meteo dla stacji w danym zakresie i renderuje tabelę. */
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

    /** Asynchronicznie ładuje pomiary hydro dla stacji w danym zakresie i renderuje tabelę. */
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
     * Lista kolumn zależy od bieżącego stanu DataTypeConfig — pola wyłączone
     * w Ustawieniach są pomijane (nie pokazujemy kolumny, którą i tak by
     * wypełniała sama wartość „niedostępne"). Konfiguracja jest czytana
     * z {@link #config} przy każdym wywołaniu, więc zmiana checkboxów odbija się
     * od razu.
     */
    private void displayMeteoData(List<MeteoData> data) {
        showTableState();

        // Czytamy DataTypeConfig na bieżąco z AppConfig — nie ze snapshotu
        // DataCollectionService — żeby włączenie/wyłączenie kolumny w Ustawieniach
        // od razu odbiło się w widoku, bez restartu. Scheduler i tak zawsze
        // zapisuje wszystkie dostępne pola, więc historyczne wartości są na dysku
        // (poza okresami, gdy kolumna była wyłączona w starszej wersji aplikacji —
        // wtedy zapisywany był null, co teraz pokażemy jako „niedostępne").
        var dtConfig = config.getDataTypeConfig();

        List<String> columns = new ArrayList<>();
        columns.add("Data i godzina pomiaru");
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

        summaryLabel.setText(buildDataSummary(data.size(), "meteo"));
    }

    /**
     * Buduje kolumny tabeli i wypełnia ją pomiarami hydro.
     * Stan wody i temperatura wody zależą od DataTypeConfig (czytanego
     * na bieżąco). Przepływ i Zjawiska są zawsze widoczne — nie mają
     * przełącznika w Ustawieniach.
     */
    private void displayHydroData(List<HydroData> data) {
        showTableState();

        // Czytamy DataTypeConfig na bieżąco z AppConfig (patrz komentarz w displayMeteoData).
        var dtConfig = config.getDataTypeConfig();

        List<String> columns = new ArrayList<>();
        columns.add("Data i godzina pomiaru");
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

        summaryLabel.setText(buildDataSummary(data.size(), "hydro"));
    }

    /**
     * Konstruuje opis pod tabelą — zamiast „0 pomiarów..." pokazuje wyraźną
     * sugestię, że dane mogą jeszcze nie zostać pobrane (np. start aplikacji
     * w trybie offline). Daje użytkownikowi konkretny krok do wykonania
     * (sprawdź połączenie / kliknij „Odśwież"), zamiast pustki bez kontekstu.
     */
    private String buildDataSummary(int count, String kind) {
        if (count == 0) {
            return "Brak danych w wybranym zakresie. Jeżeli stacja została właśnie dodana " +
                   "albo aplikacja startowała offline — sprawdź połączenie z internetem " +
                   "i kliknij „Odśwież\".";
        }
        return count + " pomiarów " + kind + " w wybranym zakresie";
    }

    /**
     * Buduje tekstowy opis zjawisk hydrologicznych (lód, zarastanie).
     *
     * @return „brak" gdy nic nie zaobserwowano (realna informacja, nie braking
     *         danych — dlatego nie „niedostępne"), inaczej oddzielone spacją
     *         nazwy zjawisk
     */
    private String describePhenomena(HydroData d) {
        if (!d.hasIcePhenomenon() && !d.hasOvergrowthPhenomenon()) return "brak";
        StringBuilder sb = new StringBuilder();
        if (d.hasIcePhenomenon())        sb.append("lód ");
        if (d.hasOvergrowthPhenomenon())  sb.append("zarastanie");
        return sb.toString().trim();
    }

    /**
     * Ustawia kolumny tabeli, odbudowuje sorter dla nowej struktury kolumn,
     * i instaluje {@link MissingValueRenderer} na kolumnach liczbowych.
     *
     * Sorter musi być tworzony na nowo (nie tylko zmieniany komparator),
     * bo liczba i znaczenie kolumn różni się między meteo a hydro —
     * stary sorter mógłby mieć komparatory przypisane do złych indeksów.
     *
     * Kolumna 0 (data pomiaru) ma komparator daty, kolumny 1..numericColumnCount
     * mają komparator liczbowy. Pozostałe (np. „Zjawiska") zostają z domyślnym
     * porównaniem alfabetycznym.
     *
     * @param columns            nagłówki kolumn
     * @param numericColumnCount liczba kolejnych kolumn liczbowych od indeksu 1
     */
    private void setColumns(String[] columns, int numericColumnCount) {
        tableModel.setColumnIdentifiers(columns);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, TableSortUtil.date());
        for (int i = 1; i <= numericColumnCount; i++) {
            sorter.setComparator(i, TableSortUtil.numeric());
        }
        dataTable.setRowSorter(sorter);

        // Renderer dla kolumn liczbowych — null pokazuje jako kursywne, szare
        // „niedostępne" zamiast pustej komórki lub myślnika. Indeks 0 to data
        // pomiaru (zawsze niepusta), więc zaczynamy od 1.
        MissingValueRenderer renderer = new MissingValueRenderer(dataTable.getFont());
        for (int i = 1; i <= numericColumnCount; i++) {
            dataTable.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
    }

    /**
     * Zwraca wartość liczbową sformatowaną do tekstu, albo null jako sentinel
     * pustej komórki. Null jest renderowany przez {@link MissingValueRenderer}
     * jako kursywne, szare „niedostępne" — co od pierwszego rzutu oka odróżnia
     * brak pomiaru od liczby zero.
     */
    private Object formatNullable(Double value) {
        return value != null ? String.format("%.1f", value) : null;
    }

    /**
     * Obsługa wyjątku z worker thread przy ładowaniu danych.
     * Loguje pełną treść, w podsumowaniu pokazuje krótki komunikat,
     * a w dialogu — pełną wiadomość błędu dla użytkownika.
     */
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

    /**
     * Renderer pozycji combo boxa stacji. Dla {@link Station} pokazuje jej
     * {@code displayLabel}; dla null (placeholder przy pustej liście) —
     * kursywne szare „— brak stacji —".
     */
    private static class StationComboRenderer extends javax.swing.DefaultListCellRenderer {
        /**
         * Renderuje element listy/combo. Dla wartości innych niż Station
         * (typowo null) ustawia kursywę i szary kolor.
         */
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

    /**
     * Renderer kolumn liczbowych — wartości null wyświetla jako kursywne, szare
     * „niedostępne". Czysta wizualnie wskazówka, że stacja nie udostępnia tego
     * parametru w danym pomiarze (np. stacja meteo bez deszczomierza nigdy nie
     * raportuje opadów). Material/Fluent: empty-state inline, brak myślnika
     * mylącego z wartością „0" lub „minus".
     */
    private static class MissingValueRenderer extends javax.swing.table.DefaultTableCellRenderer {
        private final Font baseFont;
        private final Font italicFont;

        /**
         * @param base bazowy font tabeli — używany dla wartości obecnych;
         *             italic-wariant tego fontu jest używany dla null
         */
        MissingValueRenderer(Font base) {
            this.baseFont  = base;
            this.italicFont = base.deriveFont(Font.ITALIC);
        }

        /**
         * Renderuje komórkę tabeli. Dla null pokazuje kursywne, szare
         * „niedostępne"; dla wartości obecnych — normalny styl tabeli.
         */
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