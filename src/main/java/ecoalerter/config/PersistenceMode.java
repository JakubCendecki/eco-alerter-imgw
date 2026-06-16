package ecoalerter.config;

/**
 * Tryb zapisu danych sterowany z {@code app.properties}.
 * 	persistence.mode=FILE      # zapis do JSON / CSV
 * 	persistence.mode=DATABASE  # zapis do relacyjnej bazy danych przez JDBC
*/
public enum PersistenceMode {
    FILE,
    DATABASE;
	
    /**
     * Parsuje wartość z pliku properties (wielkość liter bez znaczenia).
     * Przy nieznanej wartości zwraca {@link #FILE} i loguje ostrzeżenie.
     *
     * @param value wartość z properties (np. {@code "file"}, {@code "DATABASE"})
     * @return odpowiedni enum lub {@link #FILE} jako fallback
    */
    public static PersistenceMode fromString(String value) {
        if (value == null || value.isBlank())return FILE;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[WARN] Nieznany tryb persystencji: '" + value + "' — używam domyślnego: FILE");
            return FILE;
        }
    }
}