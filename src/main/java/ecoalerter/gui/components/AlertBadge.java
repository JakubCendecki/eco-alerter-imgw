package ecoalerter.gui.components;

import ecoalerter.model.WarningLevel;
import ecoalerter.service.WarningService;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Komponent wizualny sygnalizujący najwyższy aktywny poziom ostrzeżenia.
 *
 * Wyświetla kolorowe kółko (zgodnie z WarningLevel.getColor()) oraz licznik
 * aktywnych ostrzeżeń. Dla poziomu RED kółko miga w interwale 500 ms,
 * przyciągając uwagę użytkownika do krytycznego alertu.
 *
 * Gdy nie ma żadnych aktywnych ostrzeżeń, badge wyświetla neutralny szary
 * kolor i nie miga.
 */
public class AlertBadge extends JPanel {
	private static final long serialVersionUID = -8369646930718199966L;
	
	private static final int    DOT_SIZE        = 14;
    private static final int    BLINK_INTERVAL_MS = 500;
    private static final Color  NEUTRAL_COLOR    = new Color(150, 150, 150);

    private final DotPanel dot;
    private final JLabel   countLabel;
    private final Timer    blinkTimer;

    private WarningLevel currentLevel;
    private boolean      blinkVisible = true;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public AlertBadge() {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);

        this.dot        = new DotPanel();
        this.countLabel = new JLabel("Brak ostrzeżeń");
        this.countLabel.setFont(countLabel.getFont().deriveFont(java.awt.Font.PLAIN, 12f));

        add(dot);
        add(countLabel);

        this.blinkTimer = new Timer(BLINK_INTERVAL_MS, e -> {
            blinkVisible = !blinkVisible;
            dot.repaint();
        });

        setLevel(null, 0);
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Ustawia poziom i liczbę ostrzeżeń do wyświetlenia.
     * Automatycznie uruchamia lub zatrzymuje migotanie w zależności od poziomu.
     *
     * @param level poziom najwyższego aktywnego ostrzeżenia; null gdy brak ostrzeżeń
     * @param count łączna liczba aktywnych ostrzeżeń
     */
    public void setLevel(WarningLevel level, int count) {
        this.currentLevel = level;

        if (level == null || count == 0) {
            countLabel.setText("Brak ostrzeżeń");
            stopBlinking();
        } else {
            countLabel.setText(String.format("%s — %d aktywnych", level.getDisplayName(), count));
            if (level == WarningLevel.RED) {
                startBlinking();
            } else {
                stopBlinking();
            }
        }

        dot.repaint();
    }

    /**
     * Wygodna metoda ustawiająca badge na podstawie pełnego podsumowania
     * z WarningService.getSummary().
     *
     * @param summary agregowane dane o aktywnych ostrzeżeniach
     */
    public void setSummary(WarningService.WarningSummary summary) {
        if (summary == null || summary.isEmpty()) {
            setLevel(null, 0);
        } else {
            setLevel(summary.getHighestLevel(), summary.getTotal());
        }
    }

    // -------------------------------------------------------------------------
    // Migotanie
    // -------------------------------------------------------------------------

    private void startBlinking() {
        if (!blinkTimer.isRunning()) {
            blinkVisible = true;
            blinkTimer.start();
        }
    }

    private void stopBlinking() {
        if (blinkTimer.isRunning()) {
            blinkTimer.stop();
        }
        blinkVisible = true;
    }

    /**
     * Zatrzymuje timer migotania — wywołać przy usuwaniu komponentu z GUI,
     * aby uniknąć wycieku zasobów (Timer trzyma referencję do tego komponentu).
     */
    public void dispose() {
        blinkTimer.stop();
    }

    // =========================================================================
    // Wewnętrzny panel rysujący kółko
    // =========================================================================

    private class DotPanel extends JPanel {
		private static final long serialVersionUID = 1784319749200999755L;

		DotPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(DOT_SIZE + 4, DOT_SIZE + 4));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Color color = (currentLevel != null) ? currentLevel.getColor() : NEUTRAL_COLOR;

            // Podczas migania w fazie "niewidocznej" rysujemy przygaszony kolor,
            // a nie całkowicie pomijamy rysowanie — bardziej czytelne wizualnie
            if (currentLevel == WarningLevel.RED && !blinkVisible) {
                color = color.darker().darker();
            }

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(2, 2, DOT_SIZE, DOT_SIZE);
            g2.setColor(color.darker());
            g2.drawOval(2, 2, DOT_SIZE, DOT_SIZE);
            g2.dispose();
        }
    }
}