package ecoalerter.model;

/**
 * Typ stacji pomiarowej IMGW-PIB.
 *
 *   {@link #METEO} — stacja meteorologiczna (synop), dostarcza danych
 *    o temperaturze, wietrze, opadach i ciśnieniu atmosferycznym.
 *   {@link #HYDRO} — stacja hydrologiczna, dostarcza danych
 *   o stanie i temperaturze wody na rzekach.
*/
public enum StationType {

    /** Stacja meteorologiczna (endpoint {@code /synop}). */
    METEO("Meteorologiczna"),

    /** Stacja hydrologiczna (endpoint {@code /hydro}). */
    HYDRO("Hydrologiczna");

    private final String displayName;

    StationType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Zwraca czytelną nazwę typu stacji do wyświetlania w GUI.
     *
     * @return np. {@code "Meteorologiczna"}
    */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parsuje string na {@link StationType} (wielkość liter bez znaczenia).
     *
     * @param value string do sparsowania (np. {@code "meteo"}, {@code "HYDRO"})
     * @return odpowiedni enum lub {@link #METEO} jako domyślny przy nieznanej wartości
    */
    public static StationType fromString(String value) {
        if (value == null || value.isBlank()) return METEO;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return METEO;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }
}