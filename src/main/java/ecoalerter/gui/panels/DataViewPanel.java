package ecoalerter.gui.panels;

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
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Panel podglądu historii danych pomiarowych dla wybranej stacji.
 *
 * Zakres czasowy wybierany jest z predefiniowanej listy (ostatnia godzina,
 * 6 godzin, 24 godziny, 7 dni). Tabela danych jest budowana dynamicznie —
 * kolumny różnią się w zależności od typu stacji (METEO vs HYDRO),
 * ponieważ oba typy danych mają inny zestaw mierzonych wielkości.
 *
 * Implementuje NotificationService.AppEventListener — po zdarzeniu
 * STATIONS_CHANGED (emitowanym przez StationManagerPanel po dodaniu, usunięciu
 * lub edycji stacji) automatycznie odświeża listę stacji w combo boxie,
 * bez potrzeby ręcznego przełączania zakładek.
 */
public class DataViewPanel extends JPanel implements NotificationService.AppEventListener {

    private static final Logger log = AppLogger.get(DataViewPanel.class);

    private static final String[] METEO_COLUMNS = {
            "Czas", "Temperatura (°C)", "Wiatr (m/s)", "Opady (mm)", "Ciśnienie (hPa)"
    };
    private static final String[] HYDRO_COLUMNS = {
            "Czas", "Stan wody (cm)", "Temp. wody (°C)", "Przepływ (m³/s)", "Zjawiska"
    };

    private enum TimeRange {
        LAST_HOUR("Ostatnia godzina", 1),
        LAST_6H("Ostatnie 6 godzin", 6),
        LAST_24H("Ostatnie 24 godziny", 24),
        LAST_7D("Ostatnie 7 dni", 24 * 7);

        final String label;
        final int    hours;

        TimeRange(String label, int hours) {
            this.label = label;
            this.hours = hours;
        }

        @Override
        public String toString() { return label; }
    }

    private final StationService         stationService;
    private final DataCollectionService  dataCollectionService;
    private final NotificationService    notificationService;

    private final JComboBox<Station>    stationCombo;
    private final JComboBox<TimeRange>  rangeCombo;
    private final JButton               refreshButton;
    private final JLabel                summaryLabel;
    private final JTable                dataTable;
    private final DefaultTableModel     tableModel;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public DataViewPanel(StationService stationService,
                         DataCollectionService dataCollectionService,
                         NotificationService notificationService) {
        super(new BorderLayout());

        this.stationService        = stationService;
        this.dataCollectionService = dataCollectionService;
        this.notificationService   = notificationService;

        this.stationCombo  = new JComboBox<>();
        this.rangeCombo     = new JComboBox<>(TimeRange.values());
        this.refreshButton  = new JButton("Odśwież");
        this.summaryLabel   = new JLabel(" ");
        this.tableModel     = new DefaultTableModel(METEO_COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        this.dataTable      = new JTable(tableModel);

        stationCombo.setRenderer(new StationComboRenderer());
        rangeCombo.setSelectedItem(TimeRange.LAST_24H);
        refreshButton.addActionListener(e -> loadData());

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        toolBar.add(new JLabel("Stacja: "));
        toolBar.add(stationCombo);
        toolBar.addSeparator();
        toolBar.add(new JLabel("Zakres: "));
        toolBar.add(rangeCombo);
        toolBar.addSeparator();
        toolBar.add(refreshButton);

        JPanel summaryPanel = new JPanel(new BorderLayout());
        summaryPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        summaryPanel.add(summaryLabel, BorderLayout.WEST);

        add(toolBar, BorderLayout.NORTH);
        add(new JScrollPane(dataTable), BorderLayout.CENTER);
        add(summaryPanel, BorderLayout.SOUTH);

        notificationService.addListener(this);
        reloadStationList();
    }

    // -------------------------------------------------------------------------
    // NotificationService.AppEventListener
    // -------------------------------------------------------------------------

    @Override
    public void onEvent(NotificationService.AppEvent event) {
        if (event.getType() == NotificationService.EventType.STATIONS_CHANGED) {
            reloadStationList();
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
     */
    public void reloadStationList() {
        new SwingWorker<List<Station>, Void>() {
            @Override
            protected List<Station> doInBackground() throws Exception {
                return stationService.getAllStations();
            }

            @Override
            protected void done() {
                try {
                    Station previouslySelected = (Station) stationCombo.getSelectedItem();
                    stationCombo.removeAllItems();
                    for (Station s : get()) {
                        stationCombo.addItem(s);
                    }
                    restoreSelection(previouslySelected);
                } catch (Exception e) {
                    log.error("Nie udało się wczytać listy stacji do podglądu danych: {}",
                            e.getMessage());
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

    // -------------------------------------------------------------------------
    // Ładowanie danych historycznych
    // -------------------------------------------------------------------------

    private void loadData() {
        Station station = (Station) stationCombo.getSelectedItem();
        if (station == null) {
            JOptionPane.showMessageDialog(this, "Wybierz stację z listy.",
                    "Brak stacji", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        TimeRange range = (TimeRange) rangeCombo.getSelectedItem();
        LocalDateTime to   = LocalDateTime.now();
        LocalDateTime from = to.minusHours(range.hours);

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
        setColumns(METEO_COLUMNS);
        tableModel.setRowCount(0);

        for (MeteoData d : data) {
            tableModel.addRow(new Object[]{
                    DateTimeUtil.toDisplayString(d.getTimestamp()),
                    formatNullable(d.getTemperature()),
                    formatNullable(d.getWindSpeed()),
                    formatNullable(d.getPrecipitation()),
                    formatNullable(d.getPressure())
            });
        }

        summaryLabel.setText(data.size() + " pomiarów meteo w wybranym zakresie");
    }

    private void displayHydroData(List<HydroData> data) {
        setColumns(HYDRO_COLUMNS);
        tableModel.setRowCount(0);

        for (HydroData d : data) {
            String phenomena = describePhenomena(d);
            tableModel.addRow(new Object[]{
                    DateTimeUtil.toDisplayString(d.getTimestamp()),
                    formatNullable(d.getWaterLevel()),
                    formatNullable(d.getWaterTemperature()),
                    formatNullable(d.getFlow()),
                    phenomena
            });
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

    private void setColumns(String[] columns) {
        tableModel.setColumnIdentifiers(columns);
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
            String text = (value instanceof Station s) ? s.getDisplayLabel() : "—";
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        }
    }
}