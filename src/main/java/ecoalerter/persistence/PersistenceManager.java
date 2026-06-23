package ecoalerter.persistence;

import ecoalerter.config.AppConfig;
import ecoalerter.config.PersistenceMode;
import ecoalerter.persistence.db.ConnectionPool;
import ecoalerter.persistence.db.DatabaseRepository;
import ecoalerter.persistence.db.SchemaInitializer;
import ecoalerter.persistence.file.FileRepository;
import ecoalerter.util.AppLogger;
import ecoalerter.util.PathResolver;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Fabryka tworząca właściwą implementację DataRepository na podstawie
 * wartości persistence.mode z app.properties.
 *
 * Tworzy dokładnie jedną instancję repozytorium na czas życia aplikacji.
 * Wywołaj create() przy starcie, a close() w shutdown hooku.
 *
 * Przykład użycia:
 *
 *   DataRepository repo = PersistenceManager.create(config, pathResolver);
 *   // ... użycie repozytorium ...
 *   repo.close();
 */
public final class PersistenceManager {

    private static final Logger log = AppLogger.get(PersistenceManager.class);

    // -------------------------------------------------------------------------
    // Fabryka
    // -------------------------------------------------------------------------

    /**
     * Tworzy i konfiguruje implementację DataRepository zgodnie z trybem z konfiguracji.
     *
     * Dla trybu DATABASE inicjalizuje pulę połączeń HikariCP i tworzy schemat bazy danych.
     * Dla trybu FILE tworzy wymagane katalogi i inicjalizuje pisarzy plików.
     *
     * @param config       załadowana konfiguracja aplikacji
     * @param pathResolver resolver ścieżek wieloplatformowych
     * @return gotowe do użycia repozytorium
     * @throws PersistenceException gdy inicjalizacja wybranego trybu się nie powiedzie
     */
    public static DataRepository create(AppConfig config, PathResolver pathResolver)
            throws PersistenceException {

        PersistenceMode mode = config.getPersistenceMode();
        log.info("Inicjalizacja trybu persystencji: {}", mode);

        return switch (mode) {
            case DATABASE -> initDatabase(config);
            case FILE     -> initFile(pathResolver);
        };
    }

    // -------------------------------------------------------------------------
    // Tryb DATABASE
    // -------------------------------------------------------------------------

    private static DataRepository initDatabase(AppConfig config) throws PersistenceException {
        log.info("Łączenie z bazą danych: {}", config.getDbUrl());

        ConnectionPool pool;
        try {
            pool = new ConnectionPool(config);
        } catch (Exception e) {
            throw new PersistenceException(
                    "Nie można zainicjalizować puli połączeń: " + e.getMessage(), e);
        }

        try {
            SchemaInitializer initializer = new SchemaInitializer(pool);
            initializer.initialize();
        } catch (PersistenceException e) {
            pool.close();
            throw e;
        }

        log.info("Baza danych gotowa");
        return new DatabaseRepository(pool);
    }

    // -------------------------------------------------------------------------
    // Tryb FILE
    // -------------------------------------------------------------------------

    private static DataRepository initFile(PathResolver pathResolver)
            throws PersistenceException {
        log.info("Inicjalizacja zapisu do plików JSON w: {}", pathResolver.getDataDir());

        try {
            pathResolver.createRequiredDirectories();
        } catch (IOException e) {
            throw new PersistenceException(
                    "Nie można utworzyć katalogów danych: " + e.getMessage(), e);
        }

        return new FileRepository(pathResolver);
    }

    // -------------------------------------------------------------------------
    // Konstruktor prywatny
    // -------------------------------------------------------------------------

    private PersistenceManager() {
        // klasa narzędziowa — brak instancji
    }
}