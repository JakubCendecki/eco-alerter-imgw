package ecoalerter.gui.dialogs;

import ecoalerter.model.Station;
import ecoalerter.model.StationType;

import javax.swing.JButton;
import javax.swing.JCheckBox;
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
 * Modalny dialog do dodawania nowej stacji pomiarowej lub edycji istniejącej.
 *
 * W trybie dodawania (showAddDialog) wszystkie pola są edytowalne.
 * W trybie edycji (showEditDialog) pola ID i Typ są zablokowane — stanowią
 * złożony klucz identyfikujący rekord w repozytorium, więc ich zmiana
 * oznaczałaby w praktyce inny rekord, a nie edycję istniejącego. Edytowalne
 * pozostają: Nazwa, Interwał i Aktywność.
 *
 * Walidacja przed potwierdzeniem:
 * - ID stacji nie może być puste (tylko tryb dodawania),
 * - Nazwa nie może być pusta,
 * - Interwał musi być co najmniej 60 sekund.
 *
 * Dialog nie wykonuje żadnej komunikacji z API ani repozytorium —
 * zwraca jedynie zbudowany obiekt Station, a zapis wykonuje wywołujący panel
 * przez StationService.addStation() lub StationService.editStation().
 */
public class AddStationDialog extends JDialog {
	private static final long serialVersionUID = -2753729171890014580L;
	
	private final JTextField        idField;
    private final JTextField        nameField;
    private final JComboBox<StationType> typeCombo;
    private final JSpinner          intervalSpinner;
    private final JCheckBox         activeCheckBox;

    private Station result;
    private boolean  confirmed;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    private AddStationDialog(Window owner, boolean editMode, Station existing) {
        super(owner, editMode ? "Edytuj stację" : "Dodaj stację pomiarową",
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        idField         = new JTextField(15);
        nameField       = new JTextField(20);
        typeCombo       = new JComboBox<>(StationType.values());
        intervalSpinner = new JSpinner(new SpinnerNumberModel(300, 60, 86_400, 60));
        activeCheckBox  = new JCheckBox("Aktywna", true);

        if (editMode && existing != null) {
            idField.setText(existing.getId());
            nameField.setText(existing.getName());
            typeCombo.setSelectedItem(existing.getType());
            intervalSpinner.setValue(
                    existing.getIntervalSeconds() > 0 ? existing.getIntervalSeconds() : 300);
            activeCheckBox.setSelected(existing.isActive());

            // ID i typ są częścią identyfikatora rekordu — blokujemy edycję.
            // setEnabled(false) zamiast setEditable(false) — wyszarza pole
            // wizualnie, żeby było widać, że nie da się go zmienić.
            idField.setEnabled(false);
            typeCombo.setEnabled(false);
        }

        setLayout(new BorderLayout(10, 10));
        add(buildFormPanel(editMode), BorderLayout.CENTER);
        add(buildButtonPanel(editMode), BorderLayout.SOUTH);

        setMinimumSize(new Dimension(380, 250));
        pack();
        setLocationRelativeTo(owner);
    }

    // -------------------------------------------------------------------------
    // Budowanie UI
    // -------------------------------------------------------------------------

    private JPanel buildFormPanel(boolean editMode) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(15, 15, 5, 15));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;

        addRow(panel, gbc, 0, "ID stacji (IMGW):", idField);
        addRow(panel, gbc, 1, "Nazwa:",            nameField);
        addRow(panel, gbc, 2, "Typ:",               typeCombo);
        addRow(panel, gbc, 3, "Interwał (s):",      intervalSpinner);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        panel.add(activeCheckBox, gbc);

        if (!editMode) {
            JLabel hint = new JLabel("ID stacji znajdziesz w danepubliczne.imgw.pl/api/data");
            hint.setFont(hint.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
            hint.setForeground(java.awt.Color.GRAY);
            gbc.gridy = 5;
            panel.add(hint, gbc);
        } else {
            JLabel hint = new JLabel("ID i typ stacji nie można zmienić po dodaniu.");
            hint.setFont(hint.getFont().deriveFont(java.awt.Font.ITALIC, 11f));
            hint.setForeground(java.awt.Color.GRAY);
            gbc.gridy = 5;
            panel.add(hint, gbc);
        }

        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints gbc, int row,
                        String labelText, JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JLabel(labelText), gbc);

        gbc.gridx = 1;
        panel.add(field, gbc);
    }

    private JPanel buildButtonPanel(boolean editMode) {
        JButton okButton     = new JButton(editMode ? "Zapisz zmiany" : "Dodaj");
        JButton cancelButton = new JButton("Anuluj");

        okButton.addActionListener(e -> onConfirm(editMode));
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

    private void onConfirm(boolean editMode) {
        String id   = idField.getText().trim();
        String name = nameField.getText().trim();

        if (id.isEmpty()) {
            showValidationError("ID stacji nie może być puste.", idField);
            return;
        }
        if (name.isEmpty()) {
            showValidationError("Nazwa stacji nie może być pusta.", nameField);
            return;
        }

        StationType type     = (StationType) typeCombo.getSelectedItem();
        int         interval = (Integer) intervalSpinner.getValue();
        boolean     active   = activeCheckBox.isSelected();

        this.result    = new Station(id, name, type, active, interval);
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
     * Wyświetla modalny dialog dodawania nowej stacji i blokuje do momentu zamknięcia.
     *
     * @param parent komponent rodzica do wycentrowania dialogu (może być null)
     * @return zbudowana stacja gdy użytkownik potwierdził, empty gdy anulował
     */
    public static Optional<Station> showAddDialog(Component parent) {
        Window owner = resolveOwner(parent);
        AddStationDialog dialog = new AddStationDialog(owner, false, null);
        dialog.setVisible(true); // blokuje do dispose()
        return dialog.confirmed ? Optional.of(dialog.result) : Optional.empty();
    }

    /**
     * Wyświetla modalny dialog edycji istniejącej stacji i blokuje do momentu zamknięcia.
     * Pola ID i Typ są wstępnie wypełnione i zablokowane do edycji.
     *
     * @param parent   komponent rodzica do wycentrowania dialogu (może być null)
     * @param existing stacja do edycji — jej ID i typ zostaną zachowane w wyniku
     * @return zaktualizowana stacja gdy użytkownik potwierdził, empty gdy anulował
     */
    public static Optional<Station> showEditDialog(Component parent, Station existing) {
        if (existing == null) return Optional.empty();

        Window owner = resolveOwner(parent);
        AddStationDialog dialog = new AddStationDialog(owner, true, existing);
        dialog.setVisible(true);
        return dialog.confirmed ? Optional.of(dialog.result) : Optional.empty();
    }

    private static Window resolveOwner(Component parent) {
        return parent != null ? javax.swing.SwingUtilities.getWindowAncestor(parent) : null;
    }
}