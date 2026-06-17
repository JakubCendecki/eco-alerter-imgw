package ecoalerter.persistence.db;

import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Inicjalizuje schemat bazy danych przy starcie aplikacji.
 *
 * Wczytuje plik schema.sql z classpath i wykonuje każdą instrukcję DDL osobno.
 * Wszystkie polecenia CREATE używają klauzuli IF NOT EXISTS, więc wielokrotne
 * wywołanie initialize() jest bezpieczne i nie nadpisuje istniejących danych.
 *
 * Sprawdza też wersję schematu z tabeli app_metadata i loguje ostrzeżenie
 * gdy wersja w bazie jest inna niż oczekiwana przez aplikację.
*/
public class SchemaInitializer {
    private static final Logger log = AppLogger.get(SchemaInitializer.class);

    private static final String SCHEMA_FILE     = "schema.sql";
    private static final int    EXPECTED_VERSION = 1;

    private final ConnectionPool pool;

    /** @param pool gotowa pula połączeń do bazy danych */
    public SchemaInitializer(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Wczytuje schema.sql z classpath i wykonuje wszystkie instrukcje DDL.
     * Bezpieczne przy wielokrotnym wywołaniu — używa IF NOT EXISTS.
     *
     * @throws PersistenceException gdy nie można odczytać pliku lub wykonać SQL
    */
    public void initialize() throws PersistenceException {
        log.info("Inicjalizacja schematu bazy danych z pliku: {}", SCHEMA_FILE);

        String sql = loadSchemaFile();
        List<String> statements = splitStatements(sql);

        log.debug("Znaleziono {} instrukcji DDL do wykonania", statements.size());

        try (Connection conn = pool.getConnection();
             Statement stmt  = conn.createStatement()) {

            conn.setAutoCommit(false);
            try {
                for (String statement : statements) {
                    stmt.execute(statement);
                }
                conn.commit();
                log.info("Schemat bazy danych zainicjalizowany pomyślnie ({} instrukcji)",
                        statements.size());
            } catch (SQLException e) {
                conn.rollback();
                throw new PersistenceException("Błąd wykonania DDL: " + e.getMessage(), e);
            }

        } catch (SQLException e) {
            throw new PersistenceException("Błąd połączenia podczas inicjalizacji schematu", e);
        }

        checkSchemaVersion();
    }

    /** Wczytuje zawartość schema.sql z classpath jako String UTF-8. */
    private String loadSchemaFile() throws PersistenceException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(SCHEMA_FILE);

        if (is == null) {
            throw new PersistenceException(
                    "Plik schematu nie znaleziony na classpath: " + SCHEMA_FILE);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new PersistenceException("Błąd odczytu pliku schematu: " + e.getMessage(), e);
        }
    }

    /**
     * Dzieli plik SQL na pojedyncze instrukcje po średniku.
     * Pomija linie zaczynające się od '--' (komentarze) i puste linie.
    */
    private List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();

        StringBuilder current = new StringBuilder();
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) continue;

            current.append(line).append("\n");

            if (trimmed.endsWith(";")) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    result.add(statement);
                }
                current.setLength(0);
            }
        }

        return result;
    }

    /**
     * Odczytuje wersję schematu z tabeli app_metadata i porównuje z oczekiwaną.
     * Loguje ostrzeżenie gdy wersje się nie zgadzają (potrzebna migracja).
    */
    private void checkSchemaVersion() {
        try (Connection conn = pool.getConnection();
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(
                     "SELECT value FROM app_metadata WHERE key = 'schema_version'")) {

            if (rs.next()) {
                int version = Integer.parseInt(rs.getString("value"));
                if (version != EXPECTED_VERSION) {
                    log.warn("Wersja schematu bazy ({}) różni się od oczekiwanej ({}) — " +
                             "może być wymagana migracja danych", version, EXPECTED_VERSION);
                } else {
                    log.debug("Wersja schematu zgodna: {}", version);
                }
            }

        } catch (SQLException | NumberFormatException e) {
            log.warn("Nie można sprawdzić wersji schematu: {}", e.getMessage());
        }
    }
}