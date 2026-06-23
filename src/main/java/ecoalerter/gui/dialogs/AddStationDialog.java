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
import javax.swing.JSlider;
import javax.swing.JTextField;
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
 * - Interwał wybierany jest slajderem w zakresie 1-60 minut (przeliczany
 *   na sekundy przy zapisie, bo Station.intervalSeconds operuje w sekundach).
 *
 * Dialog nie wykonuje żadnej komunikacji z API ani repozytorium —
 * zwraca jedynie zbudowany obiekt Station, a zapis wykonuje wywołujący panel
 * przez StationService.addStation() lub StationService.editStation().
 */
public class AddStationDialog extends JDialog {

    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 30;
    private static final int DEFAULT_INTERVAL_MINUTES = 5;

    private final JTextField        idField;
    private final JTextField        nameField;
    private final JComboBox<StationType> typeCombo;
    private final JSlider           intervalSlider;
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
        intervalSlider  = buildIntervalSlider();
        activeCheckBox  = new JCheckBox("Aktywna", true);

        if (editMode && existing != null) {
            idField.setText(existing.getId());
            nameField.setText(existing.getName());
            typeCombo.setSelectedItem(existing.getType());

            int existingMinutes = existing.getIntervalSeconds() > 0
                    ? existing.getIntervalSeconds() / 60
                    : DEFAULT_INTERVAL_MINUTES;
            intervalSlider.setValue(clampToSliderRange(existingMinutes));

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

        setMinimumSize(new Dimension(420, 280));
        pack();
        setLocationRelativeTo(owner);
    }

    // -------------------------------------------------------------------------
    // Budowanie UI
    // -------------------------------------------------------------------------

    private JSlider buildIntervalSlider() {
        JSlider slider = new JSlider(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES);
        slider.setMinorTickSpacing(1);
        slider.setMajorTickSpacing(5);
        slider.setSnapToTicks(true);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true); // minimum=5 i majorTickSpacing=5 dają etykiety 5,10,15,20,25,30 automatycznie
        slider.setPreferredSize(new Dimension(260, 45));
        return slider;
    }

    private int clampToSliderRange(int minutes) {
        return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
    }

    private JPanel buildFormPanel(boolean editMode) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(15, 15, 5, 15));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;

        addRow(panel, gbc, 0, "ID stacji (IMGW):", idField);
        addRow(panel, gbc, 1, "Nazwa:",            nameField);
        addRow(panel, gbc, 2, "Typ:",               typeCombo);
        addRow(panel, gbc, 3, "Interwał (min):",    intervalSlider);

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
        int         interval = intervalSlider.getValue() * 60; // minuty -> sekundy
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