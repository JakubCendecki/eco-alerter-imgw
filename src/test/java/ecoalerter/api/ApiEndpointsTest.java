package ecoalerter.api;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Testy jednostkowe klasy ApiEndpoints.
 * Weryfikują poprawność budowania URL-i i niezmienność stałych.
*/
public class ApiEndpointsTest {

    @Test
    public void baseUrl_isNotNullOrBlank() {
        assertNotNull(ApiEndpoints.BASE_URL);
        assertFalse(ApiEndpoints.BASE_URL.isBlank());
    }

    @Test
    public void baseUrl_startsWithHttps() {
        assertTrue(ApiEndpoints.BASE_URL.startsWith("https://"));
    }

    @Test
    public void allEndpointConstants_startWithSlash() {
        assertTrue(ApiEndpoints.METEO_ALL.startsWith("/"));
        assertTrue(ApiEndpoints.METEO_BY_ID.startsWith("/"));
        assertTrue(ApiEndpoints.HYDRO_ALL.startsWith("/"));
        assertTrue(ApiEndpoints.HYDRO_BY_ID.startsWith("/"));
        assertTrue(ApiEndpoints.WARNINGS_METEO.startsWith("/"));
        assertTrue(ApiEndpoints.WARNINGS_HYDRO.startsWith("/"));
    }

    @Test
    public void fullUrl_withEndpoint_prependsBaseUrl() {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.METEO_ALL);
        assertEquals(ApiEndpoints.BASE_URL + ApiEndpoints.METEO_ALL, url);
    }

    @Test
    public void fullUrl_withHydroEndpoint_returnsCorrectUrl() {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.HYDRO_ALL);
        assertTrue(url.contains("hydro"));
        assertTrue(url.startsWith("https://"));
    }

    @Test
    public void fullUrl_withStationId_appendsParamToUrl() {
        String stationId = "12200";
        String url = ApiEndpoints.fullUrl(ApiEndpoints.METEO_BY_ID, stationId);

        assertTrue(url.endsWith(stationId));
        assertTrue(url.contains(ApiEndpoints.METEO_BY_ID));
    }

    @Test
    public void fullUrl_withHydroStationId_buildsCorrectUrl() {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.HYDRO_BY_ID, "150180180");
        assertEquals(ApiEndpoints.BASE_URL + ApiEndpoints.HYDRO_BY_ID + "150180180", url);
    }

    @Test
    public void fullUrl_withEmptyParam_stillBuildsUrl() {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.METEO_BY_ID, "");
        assertNotNull(url);
        assertTrue(url.startsWith(ApiEndpoints.BASE_URL));
    }
}