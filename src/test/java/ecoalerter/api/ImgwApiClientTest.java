package ecoalerter.api;

import ecoalerter.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe klasy ImgwApiClient.
 *
 * HttpClient jest mockowany przez package-private konstruktor testowy.
 * Testowana jest logika retry, obsługa błędów HTTP i błędów sieciowych.
*/
@ExtendWith(MockitoExtension.class)
class ImgwApiClientTest {

    @Mock private AppConfig              mockConfig;
    @Mock private HttpClient             mockHttpClient;
    @Mock private HttpResponse<String>   mockResponse;

    private ImgwApiClient client;

    @BeforeEach
    void setUp() {
        when(mockConfig.getApiTimeoutSeconds()).thenReturn(10);
        when(mockConfig.getApiRetryCount()).thenReturn(2);
        // Konstruktor testowy — wstrzykuje mock HttpClient i zeruje delay retry
        client = new ImgwApiClient(mockConfig, mockHttpClient);
    }

    // -------------------------------------------------------------------------
    // Scenariusze sukcesu
    // -------------------------------------------------------------------------

    @Test
    void get_onHttp200_returnsBody() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"test\":\"ok\"}");
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        String result = client.get("https://test.example.com/api");

        assertEquals("{\"test\":\"ok\"}", result);
    }

    @Test
    void get_onHttp200_callsHttpClientOnce() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("[]");
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        client.get("https://test.example.com/api");

        verify(mockHttpClient, times(1)).send(any(HttpRequest.class), any());
    }

    // -------------------------------------------------------------------------
    // Błędy HTTP bez retry
    // -------------------------------------------------------------------------

    @Test
    void get_onHttp404_throwsApiExceptionImmediately() throws Exception {
        when(mockResponse.statusCode()).thenReturn(404);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        ApiException ex = assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/missing"));

        assertEquals(404, ex.getHttpStatusCode());
        assertTrue(ex.isNotFound());
        // 404 nie jest retryable — tylko jedna próba
        verify(mockHttpClient, times(1)).send(any(), any());
    }

    @Test
    void get_onHttp403_throwsApiExceptionWithStatus() throws Exception {
        when(mockResponse.statusCode()).thenReturn(403);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        ApiException ex = assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/forbidden"));

        assertEquals(403, ex.getHttpStatusCode());
        assertTrue(ex.isHttpError());
        verify(mockHttpClient, times(1)).send(any(), any());
    }

    // -------------------------------------------------------------------------
    // Logika retry przy błędach przejściowych
    // -------------------------------------------------------------------------

    @Test
    void get_onHttp500_retriesAndEventuallyThrows() throws Exception {
        when(mockResponse.statusCode()).thenReturn(500);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/api"));

        // retry = 2, więc łącznie 3 próby (1 + 2 retry)
        verify(mockHttpClient, times(3)).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void get_onHttp503_retriesAndSucceedsOnSecondAttempt() throws Exception {
        HttpResponse<String> failResponse    = mock(HttpResponse.class);
        HttpResponse<String> successResponse = mock(HttpResponse.class);

        when(failResponse.statusCode()).thenReturn(503);
        when(successResponse.statusCode()).thenReturn(200);
        when(successResponse.body()).thenReturn("{\"data\":\"ok\"}");

        doReturn(failResponse)
                .doReturn(successResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        String result = client.get("https://test.example.com/api");

        assertEquals("{\"data\":\"ok\"}", result);
        verify(mockHttpClient, times(2)).send(any(), any());
    }

    @Test
    void get_onHttp429_retriesWithConfiguredCount() throws Exception {
        when(mockResponse.statusCode()).thenReturn(429);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/api"));

        verify(mockHttpClient, times(3)).send(any(), any()); // 1 + 2 retry
    }

    // -------------------------------------------------------------------------
    // Błędy sieciowe
    // -------------------------------------------------------------------------

    @Test
    void get_onIOException_throwsApiExceptionAsNetworkError() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("connection refused"));

        ApiException ex = assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/api"));

        assertTrue(ex.isNetworkError());
        assertNotNull(ex.getCause());
    }

    @Test
    void get_onIOException_retriesBeforeThrowing() throws Exception {
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("timeout"));

        assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/api"));

        verify(mockHttpClient, times(3)).send(any(), any()); // retry bo błąd sieciowy
    }

    @Test
    void get_networkErrorFollowedBySuccess_returnsBody() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"recovered\":true}");

        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("temporary failure"))
                .thenReturn(null); // wymuszamy drugi call przez doReturn poniżej

        doReturn(mockResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        // reset i ustawienie sekwencji poprawnie
        reset(mockHttpClient);
        when(mockHttpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("temporary failure"));
        doReturn(mockResponse)
                .when(mockHttpClient).send(any(HttpRequest.class), any());

        // Właściwy test z jawną sekwencją przez Answer
        reset(mockHttpClient);
        final int[] callCount = {0};
        when(mockHttpClient.send(any(HttpRequest.class), any())).thenAnswer(inv -> {
            callCount[0]++;
            if (callCount[0] == 1) throw new IOException("temporary failure");
            return mockResponse;
        });

        String result = client.get("https://test.example.com/api");

        assertEquals("{\"recovered\":true}", result);
        assertEquals(2, callCount[0]);
    }

    // -------------------------------------------------------------------------
    // Pusta odpowiedź
    // -------------------------------------------------------------------------

    @Test
    void get_onEmptyBody_throwsApiException() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("");
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        ApiException ex = assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/api"));

        assertEquals(200, ex.getHttpStatusCode());
    }

    @Test
    void get_onNullBody_throwsApiException() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(null);
        doReturn(mockResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

        assertThrows(ApiException.class,
                () -> client.get("https://test.example.com/api"));
    }
}