package ecoalerter.api;

/**
 * Stałe zawierające wszystkie endpointy publicznego REST API IMGW-PIB.
 * Dokumentacja: https://danepubliczne.imgw.pl/pl/apiinfo
*/
public final class ApiEndpoints {

    public static final String BASE_URL = "https://danepubliczne.imgw.pl/api/data";

    // -------------------------------------------------------------------------
    // Dane meteorologiczne (METEO)
    // -------------------------------------------------------------------------

    /** Wszystkie aktywne stacje meteo — zwraca tablicę JSON */
    public static final String METEO_ALL = "/meteo";

    /** Dane konkretnej stacji meteo wg ID — zwraca obiekt JSON */
    public static final String METEO_BY_ID = "/meteo/id/";

    // -------------------------------------------------------------------------
    // Dane hydrologiczne (HYDRO)
    // -------------------------------------------------------------------------

    /** Wszystkie aktywne stacje hydro — zwraca tablicę JSON */
    public static final String HYDRO_ALL = "/hydro";

    /** Dane konkretnej stacji hydro wg ID — zwraca obiekt JSON */
    public static final String HYDRO_BY_ID = "/hydro/id/";

    // -------------------------------------------------------------------------
    // Ostrzeżenia (WARNINGS)
    // Gdy brak alertów, zwraca: {"status":false,"message":"No products were found"}
    // -------------------------------------------------------------------------

    /** Bieżące ostrzeżenia meteorologiczne */
    public static final String WARNINGS_METEO = "/warningsmeteo";

    /** Bieżące ostrzeżenia hydrologiczne */
    public static final String WARNINGS_HYDRO = "/warningshydro";

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
     * @param endpointPrefix np. {@link #METEO_BY_ID}
     * @param param          wartość parametru (ID lub nazwa)
     * @return pełny URL
    */
    public static String fullUrl(String endpointPrefix, String param) {
        return BASE_URL + endpointPrefix + param;
    }

    private ApiEndpoints() { }
}