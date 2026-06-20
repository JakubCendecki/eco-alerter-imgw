package ecoalerter.gui.dialogs;

import ecoalerter.model.Station;
import ecoalerter.model.StationType;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Optional;

/**
 * Modalny dialog do dodawania nowej stacji pomiarowej.
 *
 * Walidacja przed potwierdzeniem:
 * - ID stacji nie może być puste,
 * - Nazwa nie może być pusta,
 * - Interwał musi być co najmniej 60 sekund.
 *
 * Dialog nie wykonuje żadnej komunikacji z API ani repozytorium —
 * zwraca jedynie zbudowany obiekt Station, a zapis i ewentualną walidację
 * istnienia stacji w API IMGW wykonuje wywołujący panel.
 */
public class AddStationDialog extends JDialog {
	private static final long serialVersionUID = -1795563334955791484L;
	
	private final JTextField        idField;
    private final JTextField        nameField;
    private final JComboBox<StationType> typeCombo;
    private final JSpinner          intervalSpinner;

    private Station result;
    private boolean  confirmed;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    private AddStationDialog(Window owner) {
        super(owner, "Dodaj stację pomiarową", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        idField         = new JTextField(15);
        nameField       = new JTextField(20);
        typeCombo       = new JComboBox<>(StationType.values());
        intervalSpinner = new JSpinner(new SpinnerNumberModel(300, 60, 86400, 60));

        setLayout(new BorderLayout(10, 10));
        add(buildFormPanel(), BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        getRootPane().setDefaultButton(null); // ustawiane w buildButtonPanel
        setMinimumSize(new Dimension(380, 220));
        pack();
        setLocationRelativeTo(owner);
    }

    // -------------------------------------------------------------------------
    // Budowanie UI
    // -------------------------------------------------------------------------

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(15, 15, 5, 15));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;

        addRow(panel, gbc, 0, "ID stacji (IMGW):", idField);
        addRow(panel, gbc, 1, "Nazwa:",            nameField);
        addRow(panel, gbc, 2, "Typ:",               typeCombo);
        addRow(panel, gbc, 3, "Interwał (s):",      intervalSpinner);

        JLabel hint = new JLabel("ID stacji znajdziesz w danepubliczne.imgw.pl/api/data");
        hint.setFont(hint.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
        hint.setForeground(java.awt.Color.GRAY);
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        panel.add(hint, gbc);

        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, int row,
                        String labelText, JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        panel.add(field, gbc);
    }

    private JPanel buildButtonPanel() {
        JButton okButton     = new JButton("Dodaj");
        JButton cancelButton = new JButton("Anuluj");

        okButton.addActionListener(e -> onConfirm());
        cancelButton.addActionListener(e -> onCancel());

        getRootPane().setDefaultButton(okButton);

        JPanel panel = new JPanel();
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 10, 10, 10));
        panel.add(okButton);
        panel.add(cancelButton);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Logika walidacji i potwierdzenia
    // -------------------------------------------------------------------------

    private void onConfirm() {
        String id   = idField.getText().trim();
        String name = nameField.getText().trim();

        if (id.isEmpty()) {
            showValidationError("ID stacji nie może być puste.", idField);
            return;
        }
        if (name.isEmpty()) {
            showValidationError("Nazwa stacji nie może być puste.", nameField);
            return;
        }

        StationType type     = (StationType) typeCombo.getSelectedItem();
        int         interval = (Integer) intervalSpinner.getValue();

        this.result    = new Station(id, name, type, true, interval);
        this.confirmed = true;
        dispose();
    }

    private void onCancel() {
        this.confirmed = false;
        dispose();
    }

    private void showValidationError(String message, JComponent fieldToFocus) {
        JOptionPane.showMessageDialog(this, message,
                "Błąd walidacji", JOptionPane.WARNING_MESSAGE);
        fieldToFocus.requestFocusInWindow();
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs statyczny
    // -------------------------------------------------------------------------

    /**
     * Wyświetla modalny dialog dodawania stacji i blokuje do momentu zamknięcia.
     *
     * @param parent komponent rodzica do wycentrowania dialogu (może być null)
     * @return zbudowana stacja gdy użytkownik potwierdził, empty gdy anulował
     */
    public static Optional<Station> showDialog(Component parent) {
        Window owner = parent != null
                ? javax.swing.SwingUtilities.getWindowAncestor(parent)
                : null;

        AddStationDialog dialog = new AddStationDialog(owner);
        dialog.setVisible(true); // blokuje do dispose()

        return dialog.confirmed ? Optional.of(dialog.result) : Optional.empty();
    }
}