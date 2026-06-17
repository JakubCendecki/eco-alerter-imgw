package ecoalerter.persistence;

/**
 * Wyjątek rzucany przez implementacje DataRepository przy każdym błędzie
 * zapisu lub odczytu danych — zarówno SQL jak i I/O (pliki).
 *
 * Pozwala warstwie serwisowej obsługiwać błędy persystencji jednolicie,
 * bez znajomości konkretnej implementacji repozytorium.
*/
public class PersistenceException extends Exception {
	private static final long serialVersionUID = -2484430056102445094L;

	/**
     * Tworzy wyjątek z opisem błędu.
     *
     * @param message opis błędu
    */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Tworzy wyjątek z opisem błędu i pierwotną przyczyną.
     *
     * @param message opis błędu
     * @param cause   pierwotny wyjątek (np. SQLException, IOException)
    */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}