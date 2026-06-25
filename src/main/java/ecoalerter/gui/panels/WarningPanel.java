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
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel wyświetlający bieżące ostrzeżenia meteorologiczne i hydrologiczne.
 */
public class WarningPanel extends JPanel implements NotificationService.AppEventListener {

    private static final long serialVersionUID = -3840173255326655471L;
    private static final Logger log = AppLogger.get(WarningPanel.class);

    private static final String[] COLUMNS = {
            "Poziom", "Typ", "Zjawisko", "Prawdopodobieństwo (%)", "Wydano", "Ważne do"
    };

    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";

    private static final int BUTTON_GAP = 16;

    private final WarningService      warningService;
    private final NotificationService notificationService;

    private final JComboBox<FilterOption> filterCombo;
    private final JButton                  refreshButton;
    private final JLabel                   lastRefreshLabel;
    private final JTable                   table;
    private final WarningTableModel        tableModel;
    private final JPanel                   centerPanel;
    private final JLabel                   emptyStateLabel;
    private final JLabel                   summaryLabel; // NOWA ETYKIETA

    private enum FilterOption {
        ALL("Wszystkie", null),
        ORANGE_PLUS("Pomarańczowe i wyżej", WarningLevel.ORANGE),
        RED_ONLY("Tylko czerwone", WarningLevel.RED);

        final String        label;
        final WarningLevel minLevel;

        FilterOption(String label, WarningLevel minLevel) {
            this.label = label;
            this.minLevel = minLevel;
        }

        @Override
        public String toString() { return label; }
    }

    public WarningPanel(WarningService warningService, NotificationService notificationService) {
        super(new BorderLayout());

        this.warningService      = warningService;
        this.notificationService = notificationService;

        this.filterCombo      = new JComboBox<>(FilterOption.values());
        this.refreshButton    = new JButton("Odśwież");
        this.lastRefreshLabel = new JLabel("Nie odświeżano");
        this.tableModel       = new WarningTableModel();
        this.table            = new JTable(tableModel);

        table.setRowHeight(24);
        table.setDefaultRenderer(Object.class, new WarningRowRenderer());

        TableRowSorter<WarningTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, TableSortUtil.warningSeverity());
        sorter.setComparator(3, TableSortUtil.numeric());
        sorter.setComparator(4, TableSortUtil.date());
        sorter.setComparator(5, TableSortUtil.date());
        table.setRowSorter(sorter);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewRow < 0) return;
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    Warning w = tableModel.getWarningAt(modelRow);
                    if (w != null) showWarningDetails(w);
                }
            }
        });

        filterCombo.addActionListener(e -> applyFilterAndDisplay());
        refreshButton.addActionListener(e -> onRefreshFromApi());

        JPanel filterGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 6));
        filterGroup.add(new JLabel("Filtr: "));
        filterGroup.add(filterCombo);
        filterGroup.add(refreshButton);

        JPanel toolBar = new JPanel(new BorderLayout());
        toolBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        toolBar.add(filterGroup, BorderLayout.WEST);
        toolBar.add(lastRefreshLabel, BorderLayout.EAST);
        add(toolBar, BorderLayout.NORTH);

        this.emptyStateLabel = new JLabel(" ");
        emptyStateLabel.setFont(emptyStateLabel.getFont().deriveFont(Font.PLAIN, 14f));
        emptyStateLabel.setForeground(Color.GRAY);
        JPanel emptyStatePanel = new JPanel(new GridBagLayout());
        emptyStatePanel.add(emptyStateLabel);

        this.centerPanel = new JPanel(new CardLayout());
        centerPanel.add(new JScrollPane(table), CARD_TABLE);
        centerPanel.add(emptyStatePanel, CARD_EMPTY);
        add(centerPanel, BorderLayout.CENTER);

        // INICJALIZACJA DOLNEGO PASKA PODSUMOWANIA OSTRZEŻEŃ
        this.summaryLabel = new JLabel(" ");
        add(buildSummaryPanel(), BorderLayout.SOUTH);

        notificationService.addListener(this);
        loadActiveWarnings();
    }

    /** Tworzy estetyczny dolny panel z marginesami dla napisu podsumowania */
    private JPanel buildSummaryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        panel.add(summaryLabel, BorderLayout.WEST);
        return panel;
    }

    /** NOWA METODA: Zlicza wyświetlane ostrzeżenia z rozbiciem na poziomy i aktualizuje tekst */
    private void updateSummary(List<Warning> displayedWarnings) {
        if (displayedWarnings == null || displayedWarnings.isEmpty()) {
            summaryLabel.setText("Brak ostrzeżeń");
            return;
        }

        int total = displayedWarnings.size();
        long yellowCount = displayedWarnings.stream().filter(w -> w.getLevel() == WarningLevel.YELLOW).count();
        long orangeCount = displayedWarnings.stream().filter(w -> w.getLevel() == WarningLevel.ORANGE).count();
        long redCount    = displayedWarnings.stream().filter(w -> w.getLevel() == WarningLevel.RED).count();

        summaryLabel.setText(String.format("Wszystkie ostrzeżenia: %d (Żółte: %d, Pomarańczowe: %d, Czerwone: %d)", 
                total, yellowCount, orangeCount, redCount));
    }

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
                refreshButton.setText("Odśwież");
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

        // AKTUALIZACJA PODSUMOWANIA NA PODSTAWIE AKTUALNEJ LISTY Z MODELU TABELI
        updateSummary(tableModel.getFilteredWarnings());

        if (tableModel.getRowCount() == 0) {
            showEmptyState(tableModel.isAllEmpty());
        } else {
            showTableState();
        }
    }

    private void showEmptyState(boolean noWarningsAtAll) {
        emptyStateLabel.setText(noWarningsAtAll
                ? "Brak aktywnych ostrzeżeń."
                : "Brak ostrzeżeń spełniających wybrany filtr.");
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_EMPTY);
    }

    private void showTableState() {
        ((CardLayout) centerPanel.getLayout()).show(centerPanel, CARD_TABLE);
    }

    private void showWarningDetails(Warning w) {
        JPanel header = new JPanel();
        header.setLayout(new javax.swing.BoxLayout(header, javax.swing.BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JLabel phenomLabel = new JLabel(
                w.getPhenomenon() != null ? w.getPhenomenon() : "Ostrzeżenie");
        phenomLabel.setFont(phenomLabel.getFont().deriveFont(Font.BOLD, 15f));
        phenomLabel.setAlignmentX(LEFT_ALIGNMENT);
        header.add(phenomLabel);
        header.add(Box.createVerticalStrut(6));

        String levelText = w.getLevel() != null ? w.getLevel().getDisplayName() : "—";
        String typeText  = w.getType()  != null ? w.getType().name()             : "—";
        header.add(metaLine("Poziom: " + levelText + "    Typ: " + typeText));

        String office = (w.getOffice() != null && !w.getOffice().isBlank())
                ? w.getOffice()
                : "nie podano";
        header.add(metaLine("Wydane przez: " + office));

        String issued = w.getIssuedAt() != null
                ? DateTimeUtil.toDisplayString(w.getIssuedAt())
                : "—";
        String valid = w.getValidUntil() != null
                ? DateTimeUtil.toDisplayString(w.getValidUntil())
                : "bezterminowo";
        header.add(metaLine("Wydano: " + issued + "    Ważne do: " + valid));

        JTextArea contentArea = new JTextArea(
                w.getMessage() != null && !w.getMessage().isBlank()
                        ? w.getMessage()
                        : "(brak treści w komunikacie IMGW)");
        contentArea.setEditable(false);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setMargin(new java.awt.Insets(8, 10, 8, 10));
        contentArea.setBackground(new Color(0xFAFAFA));
        contentArea.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(contentArea);
        scroll.setPreferredSize(new Dimension(560, 280));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xDDDDDD)));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(this, panel,
                "Szczegóły ostrzeżenia", JOptionPane.PLAIN_MESSAGE);
    }

    private JLabel metaLine(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setAlignmentX(LEFT_ALIGNMENT);
        return lbl;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onEvent(NotificationService.AppEvent event) {
        if (event.getType() != NotificationService.EventType.WARNINGS_REFRESHED) return;

        List<Warning> warnings = (List<Warning>) event.getPayload();
        tableModel.setAllWarnings(warnings);
        applyFilterAndDisplay();
        lastRefreshLabel.setText("Odświeżono: " + DateTimeUtil.nowDisplay());
    }

    public void dispose() {
        notificationService.removeListener(this);
    }

    // =========================================================================
    // Model tabeli
    // =========================================================================

    private static class WarningTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1628584136547442592L;

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

        /** NOWA METODA: Pozwala pobrać aktualnie przefiltrowaną listę do zliczenia */
        List<Warning> getFilteredWarnings() {
            return filteredWarnings;
        }

        Warning getWarningAt(int row) {
            return filteredWarnings.get(row);
        }

        boolean isAllEmpty() {
            return allWarnings.isEmpty();
        }

        @Override public int     getRowCount()                          { return filteredWarnings.size(); }
        @Override public int     getColumnCount()                       { return COLUMNS.length; }
        @Override public String  getColumnName(int column)              { return COLUMNS[column]; }
        @Override public boolean isCellEditable(int row, int column)    { return false; }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Warning w = filteredWarnings.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> w.getLevel() != null ? w.getLevel().getDisplayName() : "—";
                case 1 -> w.getType()  != null ? w.getType().name()             : "—";
                case 2 -> w.getPhenomenon() != null ? w.getPhenomenon()         : "—";
                case 3 -> w.getProbability() >= 0 ? w.getProbability() + "%"     : "—";
                case 4 -> DateTimeUtil.toDisplayString(w.getIssuedAt());
                case 5 -> w.getValidUntil() != null
                        ? DateTimeUtil.toDisplayString(w.getValidUntil())
                        : "bezterminowo";
                default -> "";
            };
        }
    }

    // =========================================================================
    // Renderer wierszy
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