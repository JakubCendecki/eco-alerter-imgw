package ecoalerter.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Testy jednostkowe klasy PersistenceException. */
class PersistenceExceptionTest {

    @Test
    void constructor_messageOnly_setsMessage() {
        PersistenceException ex = new PersistenceException("błąd zapisu");
        assertEquals("błąd zapisu", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void constructor_messageAndCause_setsBoth() {
        SQLException cause = new SQLException("connection failed");
        PersistenceException ex = new PersistenceException("błąd DB", cause);

        assertEquals("błąd DB", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void isCheckedException() {
        assertTrue(Exception.class.isAssignableFrom(PersistenceException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(PersistenceException.class));
    }

    @Test
    void canBeCaughtAsException() {
        assertDoesNotThrow(() -> {
            try {
                throw new PersistenceException("test");
            } catch (PersistenceException e) {
                assertEquals("test", e.getMessage());
            }
        });
    }

    // klasa pomocnicza tylko na potrzeby tego testu
    private static class SQLException extends Exception {
		private static final long serialVersionUID = -3948910778374171056L;

		SQLException(String msg) { super(msg); }
    }
}