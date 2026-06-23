package ecoalerter.gui.components;

import ecoalerter.model.WarningLevel;
import ecoalerter.util.DateTimeUtil;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * Komparatory do sortowania kolumn tabel, których wartości są przechowywane
 * jako sformatowane stringi do wyświetlenia (np. "22.4", "75%", "22.06.2026 14:30"),
 * a nie jako surowe typy liczbowe/daty.
 *
 * Domyślny TableRowSorter sortowałby takie kolumny alfabetycznie po znakach
 * (np. "10" przed "9", "01.01.2027" przed "22.06.2026") — te komparatory
 * parsują tekst z powrotem do liczby lub daty przed porównaniem, dając
 * poprawne sortowanie rosnące/malejące po wartości.
 *
 * Wartości niesparsowalne (placeholdery jak "—") zawsze trafiają na koniec
 * przy sortowaniu rosnącym (i odpowiednio na początek przy malejącym —
 * TableRowSorter automatycznie odwraca wynik komparatora dla kierunku DESCENDING).
 *
 * Używane przez StationTable, WarningPanel i DataViewPanel —
 * wspólne miejsce, żeby nie duplikować logiki parsowania w czterech plikach.
 */
public final class TableSortUtil {

    private static final String INDEFINITE_MARKER = "bezterminowo";
    private static final String EMPTY_MARKER       = "—";

    // -------------------------------------------------------------------------
    // Komparator liczbowy
    // -------------------------------------------------------------------------

    /**
     * Komparator dla kolumn z wartościami liczbowymi sformatowanymi jako tekst
     * (np. "22.4", "300", "75%", "—"). Usuwa znaki niebędące cyframi, kropką
     * dziesiętną lub minusem przed parsowaniem — dzięki temu działa zarówno
     * dla czystych liczb jak i liczb z jednostką/symbolem ("75%" → 75.0).
     *
     * @return komparator stringów reprezentujących liczby
     */
    public static Comparator<String> numeric() {
        return (a, b) -> {
            Double da = parseLeadingNumber(a);
            Double db = parseLeadingNumber(b);

            if (da == null && db == null) return 0;
            if (da == null) return 1;  // niesparsowalne na koniec
            if (db == null) return -1;
            return Double.compare(da, db);
        };
    }

    private static Double parseLeadingNumber(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) return null;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Komparator istotności ostrzeżeń
    // -------------------------------------------------------------------------

    /**
     * Komparator dla kolumny "Poziom" w tabeli ostrzeżeń. Sortuje po
     * rzeczywistej wadze zagrożenia (Żółty, Pomarańczowy, Czerwony),
     * nie alfabetycznie — bez tego "Czerwony" sortowałby się przed
     * "Pomarańczowy" i "Żółty", co jest odwrotne do faktycznej istotności.
     *
     * Wartość niedopasowana do żadnego znanego poziomu (np. "—") trafia
     * na koniec przy sortowaniu rosnącym.
     *
     * @return komparator stringów reprezentujących nazwy poziomów ostrzeżeń
     */
    public static Comparator<String> warningSeverity() {
        return (a, b) -> {
            Integer sa = severityRank(a);
            Integer sb = severityRank(b);

            if (sa == null && sb == null) return 0;
            if (sa == null) return 1;  // niedopasowane na koniec
            if (sb == null) return -1;
            return Integer.compare(sa, sb);
        };
    }

    private static Integer severityRank(String displayName) {
        if (displayName == null) return null;
        String trimmed = displayName.trim();

        for (WarningLevel level : WarningLevel.values()) {
            if (level.getDisplayName().equalsIgnoreCase(trimmed)) {
                return level.ordinal();
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Komparator dat
    // -------------------------------------------------------------------------

    /**
     * Komparator dla kolumn z datami sformatowanymi przez DateTimeUtil
     * (np. "22.06.2026 14:30"). Rozpoznaje też placeholdery używane w GUI:
     * "—" (brak wartości) sortuje się jako najwcześniejsza możliwa data,
     * "bezterminowo" sortuje się jako najpóźniejsza możliwa data — co
     * odpowiada ich rzeczywistemu znaczeniu (brak końca = trwa najdłużej).
     *
     * @return komparator stringów reprezentujących daty
     */
    public static Comparator<String> date() {
        return Comparator.comparing(TableSortUtil::parseDateOrSentinel);
    }

    private static LocalDateTime parseDateOrSentinel(String raw) {
        if (raw == null) return LocalDateTime.MIN;

        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals(EMPTY_MARKER)) return LocalDateTime.MIN;
        if (trimmed.equalsIgnoreCase(INDEFINITE_MARKER)) return LocalDateTime.MAX;

        return DateTimeUtil.parse(trimmed).orElse(LocalDateTime.MIN);
    }

    // -------------------------------------------------------------------------
    // Konstruktor prywatny
    // -------------------------------------------------------------------------

    private TableSortUtil() {
        // klasa narzędziowa — brak instancji
    }
}