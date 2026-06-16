package pl.ecoalerter.api;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import pl.ecoalerter.config.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Niskopoziomowy klient HTTP do komunikacji z REST API IMGW-PIB.
 * Odpowiada wyłącznie za:
 *   Wysłanie żądania GET pod wskazany URL
 *   Obsługę błędów HTTP i sieciowych
 *   Logikę ponowień (retry) przy błędach przejściowych
 * Parsowanie odpowiedzi JSON leży po stronie serwisów
 * ({@link MeteoApiService}, {@link HydroApiService}, {@link WarningApiService}).
*/
public class ImgwApiClient {

    private static final Logger log = LogManager.getLogger(ImgwApiClient.class);

    /** Kody HTTP, po których warto ponowić żądanie (błędy przejściowe). */
    private static final int[] RETRYABLE_STATUS_CODES = {429, 500, 502, 503, 504};

    /** Opóźnienie między kolejnymi próbami [ms]. */
    private static final long RETRY_DELAY_MS = 1_500L;

    private final HttpClient httpClient;
    private final AppConfig  config;

    public ImgwApiClient(AppConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getApiTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        log.info("ImgwApiClient zainicjalizowany [baseUrl={}, timeout={}s, retry={}]",
                ApiEndpoints.BASE_URL,
                config.getApiTimeoutSeconds(),
                config.getApiRetryCount());
    }

    /**
     * Wykonuje żądanie GET pod pełny URL i zwraca ciało odpowiedzi jako String.
     *
     * @param url pełny URL endpointu (np. z {@link ApiEndpoints#fullUrl(String)})
     * @return treść odpowiedzi HTTP (JSON)
     * @throws ApiException gdy wszystkie próby zakończyły się błędem
    */
    public String get(String url) throws ApiException {
        log.debug("GET {}", url);

        int maxAttempts = config.getApiRetryCount() + 1;
        ApiException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String body = executeGet(url);
                if (attempt > 1) {
                    log.info("Żądanie powiodło się po {} próbach: {}", attempt, url);
                }
                return body;

            } catch (ApiException e) {
                lastException = e;

                if (!shouldRetry(e) || attempt == maxAttempts) {
                    break;
                }

                log.warn("Próba {}/{} nieudana dla {} — czekam {}ms ({})",
                        attempt, maxAttempts, url, RETRY_DELAY_MS, e.getMessage());
                sleep(RETRY_DELAY_MS);
            }
        }

        log.error("Wszystkie {} próby nieudane dla: {}", maxAttempts, url);
        throw lastException;
    }

    /** Wykonuje pojedyncze żądanie GET i zwraca ciało odpowiedzi. */
    private String executeGet(String url) throws ApiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getApiTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("User-Agent", "EcoAlerter-IMGW/1.0")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException("Błąd sieci podczas żądania: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Żądanie przerwane: " + url, e);
        }

        int status = response.statusCode();
        log.debug("HTTP {} ← {}", status, url);

        if (status >= 200 && status < 300) {
            String body = response.body();
            if (body == null || body.isBlank()) {
                throw new ApiException("Pusta odpowiedź z API: " + url, status);
            }
            return body;
        }

        throw new ApiException(
                String.format("Błąd HTTP %d dla: %s", status, url),
                status
        );
    }

    /**
     * Decyduje czy warto ponawiać żądanie po danym wyjątku.
     * Ponawiamy przy błędach sieciowych i wybranych kodach HTTP.
    */
    private boolean shouldRetry(ApiException e) {
        if (e.isNetworkError()) {
            return true;
        }
        for (int code : RETRYABLE_STATUS_CODES) {
            if (e.getHttpStatusCode() == code) {
                return true;
            }
        }
        return false;
    }

    /** Usypia wątek na podaną liczbę milisekund (obsługuje InterruptedException). */
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}