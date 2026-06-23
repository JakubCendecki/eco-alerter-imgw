package ecoalerter.config;

/**
 * Tryb zapisu danych sterowany z app.properties.
 *
 * persistence.mode=FILE      # zapis do plików JSON
 * persistence.mode=DATABASE  # zapis do relacyjnej bazy danych przez JDBC
 */
public enum PersistenceMode {

    /**
     * Dane zapisywane do plików JSON w katalogu storage.file.dir.
     * Domyślny tryb — nie wymaga konfiguracji zewnętrznej bazy danych.
     */
    FILE,

    /**
     * Dane zapisywane do relacyjnej bazy danych (domyślnie SQLite).
     * Wymaga poprawnej konfiguracji {@code db.url}, {@code db.user}, {@code db.password}.
     */
    DATABASE;

    /**
     * Parsuje wartość z pliku properties (wielkość liter bez znaczenia).
     * Przy nieznanej wartości zwraca {@link #FILE} i loguje ostrzeżenie.
     *
     * @param value wartość z properties (np. {@code "file"}, {@code "DATABASE"})
     * @return odpowiedni enum lub {@link #FILE} jako fallback
     */
    public static PersistenceMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return FILE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[WARN] Nieznany tryb persystencji: '" + value
                    + "' — używam domyślnego: FILE");
            return FILE;
        }
    }
}