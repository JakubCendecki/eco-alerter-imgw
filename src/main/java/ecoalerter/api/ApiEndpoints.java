package ecoalerter.api;

/**
 * Stałe zawierające wszystkie endpointy publicznego REST API IMGW-PIB.
 * Dokumentacja: https://danepubliczne.imgw.pl
*/
public final class ApiEndpoints {
    public static final String BASE_URL = "https://danepubliczne.imgw.pl/api/data";

    /** Wszystkie aktywne stacje meteo — zwraca tablicę JSON */
    public static final String SYNOP_ALL = "/synop";

    /** Dane konkretnej stacji meteo wg ID — zwraca obiekt JSON */
    public static final String SYNOP_BY_ID = "/synop/id/";

    /** Dane stacji meteo wg nazwy (częściowe dopasowanie) */
    public static final String SYNOP_BY_NAME = "/synop/station/";


    /** Wszystkie aktywne stacje hydro — zwraca tablicę JSON */
    public static final String HYDRO_ALL = "/hydro";

    /** Dane konkretnej stacji hydro wg ID — zwraca obiekt JSON */
    public static final String HYDRO_BY_ID = "/hydro/id/";

    /** Dane stacji hydro wg nazwy (częściowe dopasowanie) */
    public static final String HYDRO_BY_NAME = "/hydro/station/";


    public static final String WARNINGS_METEO = "/warnings/meteo";

    public static final String WARNINGS_HYDRO = "/warnings/hydro";

    /**
     * Buduje pełny URL dla endpointu.
     *
     * @param endpoint ścieżka zaczynająca się od "/"
     * @return pełny URL
    */
    public static String fullUrl(String endpoint) {
        return BASE_URL + endpoint;
    }

    /**
     * Buduje pełny URL dla endpointu z dynamicznym parametrem (np. ID stacji).
     *
     * @param endpointPrefix np. {@link #SYNOP_BY_ID}
     * @param param          wartość parametru (ID lub nazwa)
     * @return pełny URL
    */
    public static String fullUrl(String endpointPrefix, String param) {
        return BASE_URL + endpointPrefix + param;
    }
}