package ecoalerter.gui.panels;

import ecoalerter.config.AppConfig;
import ecoalerter.model.Station;
import ecoalerter.service.StationService;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.DefaultCellEditor;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel konfiguracji harmonogramu — pozwala przeglądać i edytować interwał
 * odpytywania API dla każdej stacji z osobna, oraz globalny interwał domyślny.
 *
 * Zmiana wartości w kolumnie "Interwał (s)" nie jest stosowana natychmiast —
 * użytkownik musi kliknąć "Zastosuj zmiany", co pozwala na edycję wielu
 * stacji naraz przed wysłaniem żądań do StationService (każde wywołanie
 * StationService.updateInterval() przeplanowuje zadanie w schedulerze).
 *
 * Zmiana globalnego interwału domyślnego aktualizuje konfigurację tylko
 * w pamięci (AppConfig.setRaw) — obowiązuje do końca sesji aplikacji,
 * nowe stacje dodane bez własnego interwału będą używać nowej wartości.
 * Trwałe zapisanie do app.properties wymaga edycji pliku poza aplikacją.
 */
public class SchedulerPanel extends JPanel {
	private static final long serialVersionUID = -2301292350564974112L;

	private static final Logger log = AppLogger.get(SchedulerPanel.class);

    private final StationService stationService;
    private final AppConfig      config;

    private final SchedulerTableModel tableModel;
    private final JTable              table;
    private final JSpinner            globalIntervalSpinner;
    private final JButton             applyButton;
    private final JButton             refreshButton;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public SchedulerPanel(StationService stationService, AppConfig config) {
        super(new BorderLayout());

        this.stationService = stationService;
        this.config         = config;

        this.tableModel = new SchedulerTableModel();
        this.table      = new JTable(tableModel);
        table.setRowHeight(24);

        TableColumn intervalColumn = table.getColumnModel().getColumn(3);
        intervalColumn.setCellEditor(new DefaultCellEditor(
                new javax.swing.JTextField()) {
            // Prosty edytor tekstowy z walidacją liczby >= 60 przy zatwierdzeniu
            @Override
            public boolean stopCellEditing() {
                String value = (String) getCellEditorValue();
                try {
                    int parsed = Integer.parseInt(value.trim());
                    if (parsed < 60) {
                        JOptionPane.showMessageDialog(table,
                                "Minimalny interwał to 60 sekund.",
                                "Nieprawidłowa wartość", JOptionPane.WARNING_MESSAGE);
                        return false;
                    }
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(table,
                            "Wpisz liczbę sekund.",
                            "Nieprawidłowa wartość", JOptionPane.WARNING_MESSAGE);
                    return false;
                }
                return super.stopCellEditing();
            }
        });

        this.globalIntervalSpinner = new JSpinner(
                new SpinnerNumberModel(config.getSchedulerDefaultIntervalSeconds(), 60, 86_400, 30));
        this.applyButton  = new JButton("Zastosuj zmiany");
        this.refreshButton = new JButton("Odśwież listę");

        JButton applyGlobalButton = new JButton("Zastosuj domyślny");
        applyGlobalButton.addActionListener(e -> onApplyGlobalDefault());
        applyButton.addActionListener(e -> onApplyChanges());
        refreshButton.addActionListener(e -> reloadStations());

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        toolBar.add(refreshButton);
        toolBar.addSeparator();
        toolBar.add(applyButton);

        JPanel globalPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        globalPanel.setBorder(BorderFactory.createTitledBorder("Domyślny interwał (dla nowych stacji bez własnego ustawienia)"));
        globalPanel.add(new JLabel("Interwał (s):"));
        globalPanel.add(globalIntervalSpinner);
        globalPanel.add(applyGlobalButton);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(toolBar, BorderLayout.NORTH);
        northPanel.add(globalPanel, BorderLayout.SOUTH);

        add(northPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        reloadStations();
    }

    // -------------------------------------------------------------------------
    // Ładowanie i zapisywanie
    // -------------------------------------------------------------------------

    /**
     * Przeładowuje listę stacji z repozytorium. Niezapisane zmiany w tabeli
     * zostaną utracone — wywoływać tylko gdy użytkownik tego oczekuje.
     */
    public void reloadStations() {
        new SwingWorker<List<Station>, Void>() {
            @Override
            protected List<Station> doInBackground() throws Exception {
                return stationService.getAllStations();
            }

            @Override
            protected void done() {
                try {
                    tableModel.setStations(get());
                } catch (Exception e) {
                    log.error("Nie udało się wczytać stacji do harmonogramu: {}", e.getMessage());
                }
            }
        }.execute();
    }

    private void onApplyChanges() {
        List<SchedulerTableModel.PendingChange> changes = tableModel.getPendingChanges();
        if (changes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Brak zmian do zastosowania.",
                    "Informacja", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        applyButton.setEnabled(false);

        new SwingWorker<Void, Void>() {
            private final List<String> failed = new ArrayList<>();

            @Override
            protected Void doInBackground() {
                for (SchedulerTableModel.PendingChange change : changes) {
                    try {
                        stationService.updateInterval(
                                change.stationId(), change.type(), change.newInterval());
                    } catch (Exception e) {
                        failed.add(change.stationId());
                        log.warn("Nie udało się zaktualizować interwału stacji {}: {}",
                                change.stationId(), e.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                applyButton.setEnabled(true);
                tableModel.clearPendingChanges();
                reloadStations();

                if (!failed.isEmpty()) {
                    JOptionPane.showMessageDialog(SchedulerPanel.this,
                            "Nie udało się zaktualizować interwału dla: " + String.join(", ", failed),
                            "Częściowy błąd", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void onApplyGlobalDefault() {
        int newDefault = (Integer) globalIntervalSpinner.getValue();
        String oldValue = config.getRaw("scheduler.default.interval.seconds");

        config.setRaw("scheduler.default.interval.seconds", String.valueOf(newDefault));

        AppLogger.logConfigChange("scheduler.default.interval.seconds", oldValue,
                String.valueOf(newDefault));
        log.info("Domyślny interwał schedulera zmieniony na {} s (tylko w pamięci sesji)",
                newDefault);

        JOptionPane.showMessageDialog(this,
                "Domyślny interwał ustawiony na " + newDefault + " s.\n" +
                "Zmiana obowiązuje do końca sesji aplikacji — nie jest zapisywana do app.properties.",
                "Zastosowano", JOptionPane.INFORMATION_MESSAGE);
    }

    // =========================================================================
    // Model tabeli
    // =========================================================================

    private static class SchedulerTableModel extends AbstractTableModel {
		private static final long serialVersionUID = -5268476753404732918L;
		
		private static final String[] COLUMNS = {"ID", "Nazwa", "Typ", "Interwał (s)"};

        record PendingChange(String stationId, ecoalerter.model.StationType type, int newInterval) {}

        private List<Station> stations = new ArrayList<>();
        private final java.util.Map<String, Integer> edited = new java.util.HashMap<>();

        void setStations(List<Station> newStations) {
            this.stations = new ArrayList<>(newStations);
            this.edited.clear();
            fireTableDataChanged();
        }

        List<PendingChange> getPendingChanges() {
            List<PendingChange> changes = new ArrayList<>();
            for (Station s : stations) {
                Integer newValue = edited.get(s.getId() + ":" + s.getType());
                if (newValue != null && newValue != s.getIntervalSeconds()) {
                    changes.add(new PendingChange(s.getId(), s.getType(), newValue));
                }
            }
            return changes;
        }

        void clearPendingChanges() {
            edited.clear();
        }

        @Override
        public int getRowCount() { return stations.size(); }

        @Override
        public int getColumnCount() { return COLUMNS.length; }

        @Override
        public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public boolean isCellEditable(int row, int column) { return column == 3; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Station s   = stations.get(rowIndex);
            String key  = s.getId() + ":" + s.getType();
            Integer pending = edited.get(key);

            return switch (columnIndex) {
                case 0  -> s.getId();
                case 1  -> s.getName();
                case 2  -> s.getType().name();
                case 3  -> String.valueOf(pending != null ? pending : effectiveInterval(s));
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 3) return;

            try {
                int parsed = Integer.parseInt(String.valueOf(value).trim());
                Station s  = stations.get(rowIndex);
                edited.put(s.getId() + ":" + s.getType(), Math.max(parsed, 60));
                fireTableCellUpdated(rowIndex, columnIndex);
            } catch (NumberFormatException ignored) {
                // edytor komórki już zwalidował wartość — to nie powinno się zdarzyć
            }
        }

        private int effectiveInterval(Station s) {
            return s.getIntervalSeconds() > 0 ? s.getIntervalSeconds() : 300;
        }
    }
}