package ecoalerter.persistence;

/**
 * Rzucany przez StationService.addStation gdy stacja o tym ID i typie
 * jest już zapisana w repozytorium.
 *
 * Wyodrębniony z PersistenceException, żeby GUI mogło rozróżnić ten przypadek
 * (informacja dla użytkownika, nie błąd techniczny) od prawdziwych błędów
 * zapisu (np. brak miejsca na dysku) — które wciąż lecą jako PersistenceException
 * i są pokazywane jako "Błąd" zamiast "Informacja".
 *
 * Dziedziczy po PersistenceException, więc istniejące deklaracje
 * "throws PersistenceException" nie wymagają zmian.
 */
public class DuplicateStationException extends PersistenceException {
	private static final long serialVersionUID = -7921526901030498009L;

	/**
     * @param message komunikat opisujący duplikat — typowo zawiera ID i typ
     *                stacji, żeby okno informacyjne w GUI mogło pokazać go
     *                bez dodatkowego formatowania
     */
    public DuplicateStationException(String message) {
        super(message);
    }
}