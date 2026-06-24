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
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Inicjalizuje schemat bazy danych przy starcie aplikacji.
 *
 * Wczytuje plik {@code schema.sql} z classpath i wykonuje każdą instrukcję
 * DDL osobno. Wszystkie polecenia {@code CREATE} używają klauzuli
 * {@code IF NOT EXISTS}, a {@code ALTER TABLE} są wykonywane z idempotentnym
 * fallbackiem (patrz {@link #executeIdempotent}), więc wielokrotne wywołanie
 * {@link #initialize()} jest bezpieczne i nie nadpisuje istniejących danych.
 *
 * Sprawdza też wersję schematu z tabeli {@code app_metadata} i loguje
 * ostrzeżenie gdy wersja w bazie jest inna niż oczekiwana przez aplikację.
 */
public class SchemaInitializer {

    private static final Logger log = AppLogger.get(SchemaInitializer.class);

    private static final String SCHEMA_FILE = "schema.sql";
    private static final int    EXPECTED_VERSION = 2;

    private final ConnectionPool pool;

    /** @param pool gotowa pula połączeń do bazy danych */
    public SchemaInitializer(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Wczytuje schema.sql z classpath i wykonuje wszystkie instrukcje DDL.
     * Bezpieczne przy wielokrotnym wywołaniu — używa IF NOT EXISTS dla CREATE
     * i idempotentnego fallbacku dla ALTER TABLE.
     *
     * @throws PersistenceException gdy nie można odczytać pliku lub wykonać
     *                              wymaganego (nie-idempotentnego) SQL-a
     */
    public void initialize() throws PersistenceException {
        log.info("Inicjalizacja schematu bazy danych z pliku: {}", SCHEMA_FILE);

        String sql = loadSchemaFile();
        List<String> statements = splitStatements(sql);
        log.debug("Znaleziono {} instrukcji DDL do wykonania", statements.size());

        try (Connection conn = pool.getConnection();
             Statement stmt = conn.createStatement()) {

            conn.setAutoCommit(false);
            try {
                for (String statement : statements) {
                    executeIdempotent(stmt, statement);
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

    /**
     * Wykonuje pojedynczą instrukcję DDL z obsługą idempotentnych błędów.
     *
     * {@code ALTER TABLE ... ADD COLUMN} jest używany jako prosty mechanizm
     * migracji — dla istniejących baz dokłada brakującą kolumnę, dla świeżych
     * instalacji (gdzie kolumna jest już w {@code CREATE TABLE}) rzuca błąd
     * "duplicate column name", który jest tu cicho ignorowany.
     */
    private void executeIdempotent(Statement stmt, String statement) throws SQLException {
        try {
            stmt.execute(statement);
        } catch (SQLException e) {
            if (isAlterAddColumn(statement) && isDuplicateColumnError(e)) {
                log.debug("Migracja już wykonana (kolumna istnieje): {}",
                        firstLine(statement));
                return;
            }
            throw e;
        }
    }

    /** Sprawdza czy instrukcja to {@code ALTER TABLE ... ADD COLUMN ...}. */
    private boolean isAlterAddColumn(String statement) {
        String upper = statement.toUpperCase(Locale.ROOT);
        return upper.startsWith("ALTER TABLE") && upper.contains("ADD COLUMN");
    }

    /**
     * Heurystyka rozpoznająca błąd "duplicate column" w różnych dialektach SQL:
     * SQLite: "duplicate column name", Postgres: "already exists",
     * MySQL: "Duplicate column name".
     */
    private boolean isDuplicateColumnError(SQLException e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("duplicate column")
            || lower.contains("already exists");
    }

    /** Zwraca pierwszą (nie-pustą) linię instrukcji — do logów. */
    private String firstLine(String statement) {
        for (String line : statement.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return statement;
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
     * Odczytuje wersję schematu z tabeli {@code app_metadata} i porównuje
     * z oczekiwaną. Loguje ostrzeżenie gdy wersje się nie zgadzają
     * (potrzebna migracja).
     */
    private void checkSchemaVersion() {
        try (Connection conn = pool.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT value FROM app_metadata WHERE key = 'schema_version'")) {

            if (rs.next()) {
                int version = Integer.parseInt(rs.getString("value"));
                if (version != EXPECTED_VERSION) {
                    log.warn("Wersja schematu bazy ({}) różni się od oczekiwanej ({}) — "
                            + "może być wymagana migracja danych", version, EXPECTED_VERSION);
                } else {
                    log.debug("Wersja schematu zgodna: {}", version);
                }
            }
        } catch (SQLException | NumberFormatException e) {
            log.warn("Nie można sprawdzić wersji schematu: {}", e.getMessage());
        }
    }
}