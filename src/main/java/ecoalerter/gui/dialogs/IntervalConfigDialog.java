package ecoalerter.gui.dialogs;

import ecoalerter.model.Station;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.OptionalInt;

/**
 * Modalny dialog do zmiany interwału odpytywania API dla istniejącej stacji.
 *
 * Minimalny dozwolony interwał to 60 sekund — zgodnie z ograniczeniem
 * narzucanym przez ScheduleConfig.MIN_INTERVAL_SECONDS. Dialog nie wykonuje
 * żadnej operacji na schedulerze — jedynie zwraca wybraną wartość,
 * a wywołujący panel woła StationService.updateInterval(...).
 */
public class IntervalConfigDialog extends JDialog {
	private static final long serialVersionUID = 8053484168336503213L;
	
	private static final int MIN_INTERVAL_SECONDS = 60;
    private static final int MAX_INTERVAL_SECONDS = 86_400; // 24h

    private final JSpinner intervalSpinner;

    private int     result;
    private boolean confirmed;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    private IntervalConfigDialog(Window owner, Station station) {
        super(owner, "Zmień interwał odpytywania", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        int currentInterval = station.getIntervalSeconds() > 0
                ? station.getIntervalSeconds()
                : 300;

        this.intervalSpinner = new JSpinner(
                new SpinnerNumberModel(currentInterval, MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS, 30));

        setLayout(new BorderLayout(10, 10));
        add(buildInfoPanel(station), BorderLayout.NORTH);
        add(buildFormPanel(), BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        setMinimumSize(new Dimension(340, 180));
        pack();
        setLocationRelativeTo(owner);
    }

    // -------------------------------------------------------------------------
    // Budowanie UI
    // -------------------------------------------------------------------------

    private JPanel buildInfoPanel(Station station) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(12, 15, 0, 15));

        JLabel label = new JLabel(station.getDisplayLabel());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(label);
        return panel;
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        panel.add(new JLabel("Nowy interwał (sekundy):"));
        panel.add(intervalSpinner);

        return panel;
    }

    private JPanel buildButtonPanel() {
        JButton okButton     = new JButton("Zapisz");
        JButton cancelButton = new JButton("Anuluj");

        okButton.addActionListener(e -> {
            this.result    = (Integer) intervalSpinner.getValue();
            this.confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(e -> {
            this.confirmed = false;
            dispose();
        });

        getRootPane().setDefaultButton(okButton);

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        panel.add(okButton);
        panel.add(cancelButton);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs statyczny
    // -------------------------------------------------------------------------

    /**
     * Wyświetla modalny dialog zmiany interwału i blokuje do momentu zamknięcia.
     *
     * @param parent  komponent rodzica do wycentrowania dialogu (może być null)
     * @param station stacja, dla której zmieniany jest interwał
     * @return wybrany interwał w sekundach gdy potwierdzono, empty gdy anulowano
     */
    public static OptionalInt showDialog(Component parent, Station station) {
        Window owner = parent != null
                ? javax.swing.SwingUtilities.getWindowAncestor(parent)
                : null;

        IntervalConfigDialog dialog = new IntervalConfigDialog(owner, station);
        dialog.setVisible(true);

        return dialog.confirmed ? OptionalInt.of(dialog.result) : OptionalInt.empty();
    }
}