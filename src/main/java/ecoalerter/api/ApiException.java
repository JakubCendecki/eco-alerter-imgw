package ecoalerter.api;

/**
 * Wyjątek rzucany przy błędach komunikacji z API IMGW.
 * Opakowuje szczegóły błędu HTTP oraz pierwotną przyczynę.
*/
public class ApiException extends Exception {
	private static final long serialVersionUID = -4944184368677518007L;

	private final int httpStatusCode;

    /** Błąd bez kodu HTTP (np. timeout, brak sieci). */
    public ApiException(String message) {
        super(message);
        this.httpStatusCode = -1;
    }

    /** Błąd bez kodu HTTP — z przyczyną. */
    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = -1;
    }

    /** Błąd HTTP (np. 404, 500) — z kodem statusu. */
    public ApiException(String message, int httpStatusCode) {
        super(message);
        this.httpStatusCode = httpStatusCode;
    }

    /** Błąd HTTP z kodem statusu i przyczyną. */
    public ApiException(String message, int httpStatusCode, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = httpStatusCode;
    }

    /** Zwraca kod statusu HTTP lub {@code -1} jeśli błąd nie był odpowiedzią HTTP. */
    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    /** Czy błąd wynikał z odpowiedzi HTTP serwera (statusCode >= 400). */
    public boolean isHttpError() {
        return httpStatusCode >= 400;
    }

    /** Czy błąd wynikał z braku zasobu (404 Not Found). */
    public boolean isNotFound() {
        return httpStatusCode == 404;
    }

    /** Czy błąd wynikał z przekroczenia czasu połączenia lub sieci. */
    public boolean isNetworkError() {
        return httpStatusCode == -1;
    }

    @Override
    public String toString() {
        if (isNetworkError()) {
            return "ApiException [network error]: " + getMessage();
        }
        return "ApiException [HTTP " + httpStatusCode + "]: " + getMessage();
    }
}