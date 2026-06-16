package ecoalerter.model;

import java.awt.Color;

/**
 * Poziom ostrzeżenia meteorologicznego lub hydrologicznego IMGW-PIB.
 *
 * Odpowiada trójstopniowej skali alertów stosowanej przez IMGW:
 *   {@link #YELLOW}  — 1. stopień, zjawisko może być niebezpieczne
 *   {@link #ORANGE} — 2. stopień, zjawisko jest niebezpieczne
 *   {@link #RED}    — 3. stopień, zjawisko jest bardzo niebezpieczne
 *
 * Kolejność deklaracji {@code enum} ma znaczenie — {@code ordinal()} odzwierciedla
 * wagę alertu (YELLOW=0, ORANGE=1, RED=2), co umożliwia filtrowanie operatorem
 * {@code >= minLevel.ordinal()}.
*/
public enum WarningLevel {

    /** Ostrzeżenie 1. stopnia — żółte. Zjawisko może być niebezpieczne. */
    YELLOW(1, "Żółty", new Color(255, 220, 0)),

    /** Ostrzeżenie 2. stopnia — pomarańczowe. Zjawisko jest niebezpieczne. */
    ORANGE(2, "Pomarańczowy", new Color(255, 140, 0)),

    /** Ostrzeżenie 3. stopnia — czerwone. Zjawisko jest bardzo niebezpieczne. */
    RED(3, "Czerwony", new Color(200, 0, 0));

    /** Numeryczny stopień zgodny z nomenklaturą IMGW (1–3). */
    private final int degree;

    /** Czytelna nazwa wyświetlana w GUI. */
    private final String displayName;

    /** Kolor reprezentacji wizualnej (AWT Color). */
    private final Color color;

    WarningLevel(int degree, String displayName, Color color) {
        this.degree      = degree;
        this.displayName = displayName;
        this.color       = color;
    }

    /**
     * Zwraca numeryczny stopień ostrzeżenia (1, 2 lub 3) zgodny z IMGW.
     *
     * @return stopień ostrzeżenia
    */
    public int getDegree() { return degree; }

    /**
     * Zwraca czytelną nazwę koloru do wyświetlania w GUI.
     *
     * @return np. {@code "Pomarańczowy"}
    */
    public String getDisplayName() { return displayName; }

    /**
     * Zwraca kolor AWT reprezentujący poziom ostrzeżenia.
     * Używany do kolorowania wierszy i badge'y w GUI.
     *
     * @return {@link Color} odpowiadający poziomowi
    */
    public Color getColor() { return color; }

    /**
     * Parsuje string lub liczbę na {@link WarningLevel}.
     * Akceptuje: nazwy ({@code "YELLOW"}, {@code "ORANGE"}, {@code "RED"}),
     * stopnie numeryczne ({@code "1"}, {@code "2"}, {@code "3"}) oraz
     * nazwy kolorów po polsku ({@code "żółty"}, {@code "pomarańczowy"}, {@code "czerwony"}).
     *
     * @param value string do sparsowania; wielkość liter bez znaczenia
     * @return odpowiedni {@link WarningLevel} lub {@link #YELLOW} jako domyślny
    */
    public static WarningLevel fromString(String value) {
        if (value == null || value.isBlank()) return YELLOW;

        String v = value.trim().toUpperCase();
        return switch (v) {
            case "YELLOW", "1", "ŻÓŁTY", "ZOLTY"           -> YELLOW;
            case "ORANGE", "2", "POMARAŃCZOWY", "POMARANCZOWY" -> ORANGE;
            case "RED",    "3", "CZERWONY"                  -> RED;
            default -> {
                // próba valueOf jako ostatnia deska ratunku
                try {
                    yield valueOf(v);
                } catch (IllegalArgumentException e) {
                    yield YELLOW;
                }
            }
        };
    }

    /**
     * Sprawdza czy ten poziom jest co najmniej tak poważny jak podany.
     *
     * @param minLevel minimalny wymagany poziom
     * @return {@code true} jeśli {@code this.ordinal() >= minLevel.ordinal()}
    */
    public boolean isAtLeast(WarningLevel minLevel) {
        if (minLevel == null) return true;
        return this.ordinal() >= minLevel.ordinal();
    }

    @Override
    public String toString() {
        return displayName + " (" + degree + ". stopień)";
    }
}