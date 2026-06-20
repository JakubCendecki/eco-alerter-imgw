package ecoalerter.gui.components;

import ecoalerter.service.WarningService;
import ecoalerter.util.DateTimeUtil;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.time.LocalDateTime;

/**
 * Pasek statusu wyświetlany u dołu głównego okna aplikacji.
 *
 * Prezentuje cztery grupy informacji od lewej do prawej:
 * liczba aktywnych stacji, liczba stacji w stanie krytycznym,
 * AlertBadge z podsumowaniem ostrzeżeń, oraz czas ostatniej synchronizacji.
 *
 * Wszystkie metody set* są bezpieczne do wywołania z wątku EDT —
 * MainWindow powinien wywoływać je po otrzymaniu zdarzeń z NotificationService,
 * które są już dostarczane na EDT (przez SwingUtilities.invokeLater).
 */
public class StatusBar extends JPanel {
	private static final long serialVersionUID = 7672738605640220472L;
	
	private final JLabel     activeStationsLabel;
    private final JLabel     criticalStationsLabel;
    private final AlertBadge alertBadge;
    private final JLabel     lastSyncLabel;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public StatusBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
        ));

        // --- Lewa strona: liczniki stacji ---
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        leftPanel.setOpaque(false);

        activeStationsLabel = new JLabel("Aktywne stacje: 0");
        criticalStationsLabel = new JLabel("Krytyczne: 0");
        criticalStationsLabel.setForeground(new Color(150, 0, 0));

        leftPanel.add(activeStationsLabel);
        leftPanel.add(new JSeparator(SwingConstants.VERTICAL));
        leftPanel.add(criticalStationsLabel);

        // --- Środek: badge ostrzeżeń ---
        alertBadge = new AlertBadge();

        // --- Prawa strona: ostatnia synchronizacja ---
        lastSyncLabel = new JLabel("Ostatnia synchronizacja: —");
        lastSyncLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        add(leftPanel,     BorderLayout.WEST);
        add(alertBadge,    BorderLayout.CENTER);
        add(lastSyncLabel, BorderLayout.EAST);
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Ustawia liczbę aktywnie zaplanowanych stacji (z TaskSchedulerManager.getActiveTaskCount()).
     *
     * @param count liczba aktywnych zadań w schedulerze
     */
    public void setActiveStations(int count) {
        activeStationsLabel.setText("Aktywne stacje: " + count);
    }

    /**
     * Ustawia liczbę stacji w stanie krytycznym (3+ kolejnych błędów).
     * Etykieta jest pogrubiona gdy liczba jest większa od zera.
     *
     * @param count liczba stacji krytycznych
     */
    public void setCriticalStations(long count) {
        criticalStationsLabel.setText("Krytyczne: " + count);
        criticalStationsLabel.setFont(
                criticalStationsLabel.getFont().deriveFont(
                        count > 0 ? java.awt.Font.BOLD : java.awt.Font.PLAIN));
    }

    /**
     * Aktualizuje badge ostrzeżeń na podstawie podsumowania z WarningService.
     *
     * @param summary podsumowanie aktywnych ostrzeżeń
     */
    public void setWarningSummary(WarningService.WarningSummary summary) {
        alertBadge.setSummary(summary);
    }

    /**
     * Ustawia czas ostatniej udanej synchronizacji danych.
     *
     * @param timestamp czas synchronizacji; null wyświetla "—"
     */
    public void setLastSync(LocalDateTime timestamp) {
        String text = timestamp != null
                ? DateTimeUtil.toDisplayString(timestamp)
                : "—";
        lastSyncLabel.setText("Ostatnia synchronizacja: " + text);
    }

    /**
     * Zwalnia zasoby trzymane przez wewnętrzny AlertBadge (timer migotania).
     * Wywołać przy zamykaniu aplikacji.
     */
    public void dispose() {
        alertBadge.dispose();
    }
}