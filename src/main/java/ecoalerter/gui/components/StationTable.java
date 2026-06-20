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
 * Kolumna "Aktywna" jest edytowalna w miejscu (checkbox) — zmiana wartości
 * wywołuje zarejestrowany ActiveToggleListener, który powinien zlecić
 * aktualizację przez StationService bez bezpośredniego dostępu tabeli do serwisu.
 *
 * Kolumna "Status" prezentuje stan zdrowia stacji na podstawie
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
            "Aktywna", "ID", "Nazwa", "Typ", "Interwał (s)", "Status"
    };

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
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(3).setMaxWidth(80);
        table.getColumnModel().getColumn(4).setMaxWidth(100);

        TableColumn statusColumn = table.getColumnModel().getColumn(5);
        statusColumn.setCellRenderer(new StatusCellRenderer());

        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Ustawia listę stacji do wyświetlenia, zastępując poprzednią zawartość.
     *
     * @param stations lista stacji; null jest traktowany jako pusta lista
     */
    public void setStations(List<Station> stations) {
        model.setStations(stations != null ? stations : List.of());
    }

    /**
     * Aktualizuje status zdrowia jednej stacji bez przeładowania całej tabeli.
     * Wywołuj po otrzymaniu zdarzenia z NotificationService.
     *
     * @param stationId identyfikator stacji
     * @param status    aktualny status; null czyści wpis
     */
    public void updateStatus(String stationId, NotificationService.StationStatus status) {
        model.updateStatus(stationId, status);
    }

    /**
     * Zwraca aktualnie wybraną stację lub null gdy nic nie jest wybrane.
     *
     * @return wybrana stacja lub null
     */
    public Station getSelectedStation() {
        int row = table.getSelectedRow();
        if (row < 0) return null;
        return model.getStationAt(table.convertRowIndexToModel(row));
    }

    /**
     * Rejestruje odbiornik przełączenia checkboxa "Aktywna".
     *
     * @param listener odbiornik wywoływany po zmianie stanu w GUI
     */
    public void setActiveToggleListener(ActiveToggleListener listener) {
        this.toggleListener = listener;
    }

    /**
     * Zwraca surowy komponent JTable — do zaawansowanych operacji
     * (np. dodanie własnego sortera lub listenera selekcji).
     *
     * @return wewnętrzny JTable
     */
    public JTable getTable() {
        return table;
    }

    // =========================================================================
    // Model tabeli
    // =========================================================================

    private class StationTableModel extends AbstractTableModel {
		private static final long serialVersionUID = -9007172792523411385L;
		
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
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Station s = stations.get(rowIndex);
            return switch (columnIndex) {
                case 0  -> s.isActive();
                case 1  -> s.getId();
                case 2  -> s.getName();
                case 3  -> s.getType().name();
                case 4  -> s.getIntervalSeconds() > 0
                        ? String.valueOf(s.getIntervalSeconds())
                        : "domyślny";
                case 5  -> describeStatus(statuses.get(s.getId()));
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || !(value instanceof Boolean newActive)) return;

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