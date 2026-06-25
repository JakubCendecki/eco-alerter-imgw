package ecoalerter.gui.components;

import ecoalerter.model.Station;
import ecoalerter.service.NotificationService;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Komponent tabeli wyświetlający listę stacji pomiarowych.
 *
 * Kolumna „Aktywna" jest edytowalna w miejscu (checkbox) — zmiana wartości
 * wywołuje zarejestrowany ActiveToggleListener, który powinien zlecić
 * aktualizację przez StationService bez bezpośredniego dostępu tabeli do serwisu.
 *
 * <h2>Dwie kolumny nazwy</h2>
 * <ul>
 *   <li><b>Nazwa stacji</b> — oryginalna nazwa z API IMGW (pole {@code apiName}),
 *       wypełniana automatycznie przez {@code FetchTask} po pierwszym pobraniu
 *       danych. Tylko do odczytu.</li>
 *   <li><b>Nazwa własna</b> — nazwa własna nadana przez użytkownika (pole {@code name}),
 *       edytowalna w dialogu „Edytuj...". Domyślnie taka sama jak nazwa z API,
 *       ale można ją skrócić/zmienić dla wygody.</li>
 * </ul>
 *
 * Kolumna „Status" prezentuje stan zdrowia stacji na podstawie
 * NotificationService.StationStatus — kolor tekstu zmienia się wg liczby
 * kolejnych błędów (zielony OK, żółty błąd, czerwony krytyczny).
 */
public class StationTable extends JPanel {
    private static final long serialVersionUID = 6342092560108145959L;

    /**
     * Odbiornik zmiany stanu aktywności stacji z poziomu checkboxa w tabeli.
     */
    public interface ActiveToggleListener {
        void onToggle(Station station, boolean newActive);
    }

    private static final String[] COLUMNS = {
            "Aktywna", "ID", "Nazwa stacji", "Nazwa własna", "Typ", "Interwał (s)", "Status"
    };

    // Indeksy kolumn — używane też w setMaxWidth, sorterach i rendererach.
    // Zmiana kolejności kolumn wymaga aktualizacji tych stałych.
    private static final int COL_ACTIVE   = 0;
    private static final int COL_ID       = 1;
    private static final int COL_API_NAME = 2;
    private static final int COL_NAME     = 3;
    private static final int COL_TYPE     = 4;
    private static final int COL_INTERVAL = 5;
    private static final int COL_STATUS   = 6;

    private final JTable           table;
    private final StationTableModel model;
    private ActiveToggleListener   toggleListener;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public StationTable() {
        super(new BorderLayout());

        this.model = new StationTableModel();
        this.table = new JTable(model);

        table.setRowHeight(24);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(COL_ACTIVE).setMaxWidth(70);
        table.getColumnModel().getColumn(COL_TYPE).setMaxWidth(80);
        table.getColumnModel().getColumn(COL_INTERVAL).setMaxWidth(100);

        TableColumn statusColumn = table.getColumnModel().getColumn(COL_STATUS);
        statusColumn.setCellRenderer(new StatusCellRenderer());

        // Sortowanie po kliknięciu nagłówka: 1. klik = rosnąco, 2. klik = malejąco,
        // 3. klik = powrót do układu pierwotnego. Kolumny ID i Interwał wyglądają
        // jak liczby, więc dostają komparator numeryczny — bez tego sortowałyby
        // się alfabetycznie po znakach (np. "150180180" przed "12200").
        TableRowSorter<StationTableModel> sorter = new TableRowSorter<>(model);
        sorter.setComparator(COL_ID,       TableSortUtil.numeric());
        sorter.setComparator(COL_INTERVAL, TableSortUtil.numeric());
        table.setRowSorter(sorter);

        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Ustawia listę stacji do wyświetlenia, zastępując poprzednią zawartość.
     */
    public void setStations(List<Station> stations) {
        model.setStations(stations != null ? stations : List.of());
    }

    /**
     * Aktualizuje status zdrowia jednej stacji bez przeładowania całej tabeli.
     */
    public void updateStatus(String stationId, NotificationService.StationStatus status) {
        model.updateStatus(stationId, status);
    }

    /**
     * Zwraca aktualnie wybraną stację lub null gdy nic nie jest wybrane.
     */
    public Station getSelectedStation() {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return model.getStationAt(table.convertRowIndexToModel(row));
    }

    /**
     * Rejestruje odbiornik przełączenia checkboxa „Aktywna".
     */
    public void setActiveToggleListener(ActiveToggleListener listener) {
        this.toggleListener = listener;
    }

    /**
     * Zwraca surowy komponent JTable — do zaawansowanych operacji
     * (np. dodanie własnego sortera lub listenera selekcji).
     */
    public JTable getTable() {
        return table;
    }

    // =========================================================================
    // Model tabeli
    // =========================================================================

    private class StationTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1269118445228613280L;

        private List<Station> stations = new ArrayList<>();
        private final Map<String, NotificationService.StationStatus> statuses = new HashMap<>();

        void setStations(List<Station> newStations) {
            this.stations = new ArrayList<>(newStations);
            fireTableDataChanged();
        }

        void updateStatus(String stationId, NotificationService.StationStatus status) {
            if (status == null) statuses.remove(stationId);
            else                 statuses.put(stationId, status);

            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).getId().equals(stationId)) {
                    fireTableRowsUpdated(i, i);
                    break;
                }
            }
        }

        Station getStationAt(int row) {
            return stations.get(row);
        }

        @Override
        public int getRowCount() { return stations.size(); }

        @Override
        public int getColumnCount() { return COLUMNS.length; }

        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == COL_ACTIVE ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == COL_ACTIVE;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Station s = stations.get(rowIndex);
            return switch (columnIndex) {
                case COL_ACTIVE   -> s.isActive();
                case COL_ID       -> s.getId();
                case COL_API_NAME -> (s.getApiName() != null && !s.getApiName().isBlank())
                                         ? s.getApiName()
                                         : "—";
                case COL_NAME     -> s.getName();
                case COL_TYPE     -> s.getType().name();
                case COL_INTERVAL -> s.getIntervalSeconds() > 0
                                         ? String.valueOf(s.getIntervalSeconds())
                                         : "domyślny";
                case COL_STATUS   -> describeStatus(statuses.get(s.getId()));
                default           -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != COL_ACTIVE || !(value instanceof Boolean newActive)) return;

            Station station = stations.get(rowIndex);
            if (toggleListener != null) {
                toggleListener.onToggle(station, newActive);
            }
            // Nie modyfikujemy stacji bezpośrednio — czekamy na odśwież z serwisu,
            // ale dla responsywności UI aktualizujemy lokalnie od razu:
            station.setActive(newActive);
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        private String describeStatus(NotificationService.StationStatus status) {
            if (status == null) return "—";
            if (status.isCritical()) return "Krytyczny (" + status.getConsecutiveErrors() + " błędów)";
            if (!status.isHealthy()) return "Błąd (" + status.getConsecutiveErrors() + ")";
            return "OK";
        }
    }

    // =========================================================================
    // Renderer kolumny Status
    // =========================================================================

    private class StatusCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 6485208374860362415L;

        StatusCellRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable tbl, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    tbl, value, isSelected, hasFocus, row, column);

            String text = String.valueOf(value);
            if (text.startsWith("Krytyczny")) {
                c.setForeground(isSelected ? Color.WHITE : new Color(200, 0, 0));
            } else if (text.startsWith("Błąd")) {
                c.setForeground(isSelected ? Color.WHITE : new Color(200, 120, 0));
            } else if (text.equals("OK")) {
                c.setForeground(isSelected ? Color.WHITE : new Color(0, 130, 0));
            } else {
                c.setForeground(isSelected ? Color.WHITE : Color.GRAY);
            }
            return c;
        }
    }
}