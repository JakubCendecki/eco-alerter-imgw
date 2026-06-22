package ecoalerter.gui.panels;

import ecoalerter.gui.components.TableSortUtil;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.service.NotificationService;
import ecoalerter.service.WarningService;
import ecoalerter.util.AppLogger;
import ecoalerter.util.DateTimeUtil;
import org.apache.logging.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel wyświetlający bieżące ostrzeżenia meteorologiczne i hydrologiczne.
 *
 * Wiersze tabeli są kolorowane wg poziomu ostrzeżenia (WarningLevel.getColor()),
 * co daje natychmiastową wizualną sygnalizację stanów alarmowych.
 * Filtr poziomu pozwala ograniczyć widok do ostrzeżeń od wybranego poziomu wzwyż.
 *
 * Implementuje NotificationService.AppEventListener — automatycznie odświeża
 * tabelę po zdarzeniu WARNINGS_REFRESHED, bez potrzeby manualnego klikania
 * przycisku odśwież.
 */
public class WarningPanel extends JPanel implements NotificationService.AppEventListener {
	private static final long serialVersionUID = -269700932992530268L;

	private static final Logger log = AppLogger.get(WarningPanel.class);

    private static final String[] COLUMNS = {
            "Poziom", "Typ", "Zjawisko", "Prob. (%)", "Wydano", "Ważne do"
    };

    private final WarningService      warningService;
    private final NotificationService notificationService;

    private final JComboBox<FilterOption> filterCombo;
    private final JButton                  refreshButton;
    private final JLabel                   lastRefreshLabel;
    private final JTable                   table;
    private final WarningTableModel        tableModel;

    private enum FilterOption {
        ALL("Wszystkie", null),
        ORANGE_PLUS("Pomarańczowe i wyżej", WarningLevel.ORANGE),
        RED_ONLY("Tylko czerwone", WarningLevel.RED);

        final String       label;
        final WarningLevel minLevel;

        FilterOption(String label, WarningLevel minLevel) {
            this.label    = label;
            this.minLevel = minLevel;
        }

        @Override
        public String toString() { return label; }
    }

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public WarningPanel(WarningService warningService, NotificationService notificationService) {
        super(new BorderLayout());

        this.warningService      = warningService;
        this.notificationService = notificationService;

        this.filterCombo      = new JComboBox<>(FilterOption.values());
        this.refreshButton    = new JButton("Odśwież z IMGW");
        this.lastRefreshLabel = new JLabel("Nie odświeżano");
        this.tableModel       = new WarningTableModel();
        this.table            = new JTable(tableModel);

        table.setRowHeight(24);
        table.setDefaultRenderer(Object.class, new WarningRowRenderer());

        // Sortowanie po kliknięciu nagłówka. Prob. (%) to liczba, Wydano/Ważne do
        // to daty — bez komparatorów sortowałyby się alfabetycznie po znakach
        // (np. "01.01.2027" przed "22.06.2026", co jest błędne chronologicznie).
        TableRowSorter<WarningTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, TableSortUtil.warningSeverity()); // Poziom
        sorter.setComparator(3, TableSortUtil.numeric());          // Prob. (%)
        sorter.setComparator(4, TableSortUtil.date());             // Wydano
        sorter.setComparator(5, TableSortUtil.date());             // Ważne do
        table.setRowSorter(sorter);

        filterCombo.addActionListener(e -> applyFilterAndDisplay());
        refreshButton.addActionListener(e -> onRefreshFromApi());

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        toolBar.add(new JLabel("Filtr: "));
        toolBar.add(filterCombo);
        toolBar.addSeparator();
        toolBar.add(refreshButton);
        toolBar.add(javax.swing.Box.createHorizontalGlue());
        toolBar.add(lastRefreshLabel);

        add(toolBar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        notificationService.addListener(this);
        loadActiveWarnings();
    }

    // -------------------------------------------------------------------------
    // Ładowanie danych
    // -------------------------------------------------------------------------

    /**
     * Wczytuje aktywne ostrzeżenia z repozytorium (bez odpytywania API IMGW)
     * i odświeża tabelę z zastosowaniem aktualnego filtru.
     */
    public void loadActiveWarnings() {
        new SwingWorker<List<Warning>, Void>() {
            @Override
            protected List<Warning> doInBackground() throws Exception {
                return warningService.getActiveWarnings();
            }

            @Override
            protected void done() {
                try {
                    tableModel.setAllWarnings(get());
                    applyFilterAndDisplay();
                } catch (Exception e) {
                    log.error("Błąd wczytywania aktywnych ostrzeżeń: {}", e.getMessage());
                }
            }
        }.execute();
    }

    private void onRefreshFromApi() {
        refreshButton.setEnabled(false);
        refreshButton.setText("Odświeżanie...");

        new SwingWorker<List<Warning>, Void>() {
            @Override
            protected List<Warning> doInBackground() throws Exception {
                return warningService.fetchAndSave();
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                refreshButton.setText("Odśwież z IMGW");

                try {
                    tableModel.setAllWarnings(get());
                    applyFilterAndDisplay();
                    lastRefreshLabel.setText("Odświeżono: " + DateTimeUtil.nowDisplay());
                } catch (Exception e) {
                    log.error("Błąd odświeżania ostrzeżeń z API: {}", e.getMessage());
                    JOptionPane.showMessageDialog(WarningPanel.this,
                            "Nie udało się pobrać ostrzeżeń z API IMGW:\n" + e.getMessage(),
                            "Błąd", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void applyFilterAndDisplay() {
        FilterOption selected = (FilterOption) filterCombo.getSelectedItem();
        WarningLevel minLevel = selected != null ? selected.minLevel : null;
        tableModel.applyFilter(minLevel);
    }

    // -------------------------------------------------------------------------
    // NotificationService.AppEventListener
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public void onEvent(NotificationService.AppEvent event) {
        if (event.getType() != NotificationService.EventType.WARNINGS_REFRESHED) return;

        // NotificationService.onWarningsRefreshed zawsze publikuje List<Warning>
        // (może być pusta, ale typ jest stały — patrz NotificationService.java)
        List<Warning> warnings = (List<Warning>) event.getPayload();
        tableModel.setAllWarnings(warnings);
        applyFilterAndDisplay();
        lastRefreshLabel.setText("Odświeżono: " + DateTimeUtil.nowDisplay());
    }

    /**
     * Wyrejestrowuje panel z NotificationService — wywołać przy zamykaniu zakładki/aplikacji.
     */
    public void dispose() {
        notificationService.removeListener(this);
    }

    // =========================================================================
    // Model tabeli
    // =========================================================================

    private static class WarningTableModel extends AbstractTableModel {
		private static final long serialVersionUID = -1433756110399268889L;
		
		private List<Warning> allWarnings      = new ArrayList<>();
        private List<Warning> filteredWarnings = new ArrayList<>();

        void setAllWarnings(List<Warning> warnings) {
            this.allWarnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
        }

        void applyFilter(WarningLevel minLevel) {
            if (minLevel == null) {
                filteredWarnings = new ArrayList<>(allWarnings);
            } else {
                filteredWarnings = allWarnings.stream()
                        .filter(w -> w.meetsLevel(minLevel))
                        .toList();
            }
            fireTableDataChanged();
        }

        Warning getWarningAt(int row) {
            return filteredWarnings.get(row);
        }

        @Override
        public int getRowCount() { return filteredWarnings.size(); }

        @Override
        public int getColumnCount() { return COLUMNS.length; }

        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public boolean isCellEditable(int row, int column) { return false; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Warning w = filteredWarnings.get(rowIndex);
            return switch (columnIndex) {
                case 0  -> w.getLevel() != null ? w.getLevel().getDisplayName() : "—";
                case 1  -> w.getType()  != null ? w.getType().name()           : "—";
                case 2  -> w.getPhenomenon() != null ? w.getPhenomenon()        : "—";
                case 3  -> w.getProbability() >= 0 ? w.getProbability() + "%"   : "—";
                case 4  -> DateTimeUtil.toDisplayString(w.getIssuedAt());
                case 5  -> w.getValidUntil() != null
                        ? DateTimeUtil.toDisplayString(w.getValidUntil())
                        : "bezterminowo";
                default -> "";
            };
        }
    }

    // =========================================================================
    // Renderer wierszy — kolorowanie wg poziomu ostrzeżenia
    // =========================================================================

    private class WarningRowRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 6773993286828545130L;

		@Override
        public Component getTableCellRendererComponent(JTable tbl, Object value,
                                                        boolean isSelected, boolean hasFocus,
                                                        int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    tbl, value, isSelected, hasFocus, row, column);

            Warning warning = tableModel.getWarningAt(tbl.convertRowIndexToModel(row));
            Color levelColor = warning.getDisplayColor();

            if (levelColor != null && !isSelected) {
                // Pastelowa wersja koloru jako tło wiersza — tekst zostaje czarny dla czytelności
                c.setBackground(blend(levelColor, Color.WHITE, 0.75f));
                c.setForeground(Color.BLACK);
            } else if (!isSelected) {
                c.setBackground(Color.WHITE);
                c.setForeground(Color.BLACK);
            }

            return c;
        }

        private Color blend(Color base, Color with, float ratio) {
            int r = (int) (base.getRed()   * (1 - ratio) + with.getRed()   * ratio);
            int g = (int) (base.getGreen() * (1 - ratio) + with.getGreen() * ratio);
            int b = (int) (base.getBlue()  * (1 - ratio) + with.getBlue()  * ratio);
            return new Color(r, g, b);
        }
    }
}