package ecoalerter.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy jednostkowe klasy ApiException.
 * Weryfikują klasyfikację błędów (HTTP vs sieciowe) i gettery.
*/
class ApiExceptionTest {

    @Test
    void constructor_messageOnly_setsNetworkError() {
        ApiException ex = new ApiException("timeout");
        assertEquals("timeout", ex.getMessage());
        assertEquals(-1, ex.getHttpStatusCode());
        assertTrue(ex.isNetworkError());
    }

    @Test
    void constructor_messageAndCause_preservesCause() {
        RuntimeException cause = new RuntimeException("root cause");
        ApiException ex = new ApiException("wrapped", cause);

        assertEquals("wrapped", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertTrue(ex.isNetworkError());
    }

    @Test
    void constructor_withHttpStatus_setsStatusCode() {
        ApiException ex = new ApiException("Server error", 500);
        assertEquals(500, ex.getHttpStatusCode());
        assertFalse(ex.isNetworkError());
    }

    @Test
    void constructor_withHttpStatusAndCause_setsAll() {
        Throwable cause = new RuntimeException("sql");
        ApiException ex = new ApiException("DB error", 503, cause);

        assertEquals(503, ex.getHttpStatusCode());
        assertSame(cause, ex.getCause());
    }

    @Test
    void isHttpError_returnsTrueForStatus400() {
        assertTrue(new ApiException("bad request", 400).isHttpError());
    }

    @Test
    void isHttpError_returnsTrueForStatus500() {
        assertTrue(new ApiException("server error", 500).isHttpError());
    }

    @Test
    void isHttpError_returnsFalseForNetworkError() {
        assertFalse(new ApiException("timeout").isHttpError());
    }

    @Test
    void isHttpError_returnsFalseForStatus200() {
        // 200 to sukces — nie powinien być tworzony jako błąd, ale sprawdzamy logikę
        assertFalse(new ApiException("ok?", 200).isHttpError());
    }

    @Test
    void isNotFound_returnsTrueFor404() {
        assertTrue(new ApiException("Not found", 404).isNotFound());
    }

    @Test
    void isNotFound_returnsFalseFor403() {
        assertFalse(new ApiException("Forbidden", 403).isNotFound());
    }

    @Test
    void isNotFound_returnsFalseForNetworkError() {
        assertFalse(new ApiException("timeout").isNotFound());
    }

    @Test
    void isNetworkError_returnsTrueWhenStatusMinusOne() {
        assertTrue(new ApiException("no connection").isNetworkError());
    }

    @Test
    void isNetworkError_returnsFalseWhenStatusSet() {
        assertFalse(new ApiException("error", 503).isNetworkError());
    }

    @Test
    void toString_networkError_containsNetworkErrorLabel() {
        String s = new ApiException("connection refused").toString();
        assertTrue(s.contains("network error"), "toString powinien zawierać 'network error'");
        assertTrue(s.contains("connection refused"));
    }

    @Test
    void toString_httpError_containsStatusCode() {
        String s = new ApiException("not found", 404).toString();
        assertTrue(s.contains("404"), "toString powinien zawierać kod HTTP");
        assertTrue(s.contains("not found"));
    }
}