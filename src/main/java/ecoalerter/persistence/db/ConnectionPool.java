package ecoalerter.persistence.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ecoalerter.config.AppConfig;
import ecoalerter.util.AppLogger;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Pula połączeń JDBC oparta na bibliotece HikariCP.
 *
 * Konfiguracja pobierana jest z AppConfig (klucze db.*).
 * Obsługuje SQLite, PostgreSQL i MySQL — sterownik jest wybierany
 * automatycznie przez JDBC na podstawie prefiksu URL (jdbc:sqlite:, jdbc:postgresql: itd.).
 *
 * Wywołaj close() przy zamykaniu aplikacji, aby zwolnić połączenia.
*/
public class ConnectionPool {
    private static final Logger log = AppLogger.get(ConnectionPool.class);

    private final HikariDataSource dataSource;

    /**
     * Tworzy i uruchamia pulę połączeń na podstawie konfiguracji aplikacji.
     *
     * @param config załadowana konfiguracja aplikacji
     * @throws RuntimeException gdy nawiązanie połączenia się nie powiedzie
     */
    public ConnectionPool(AppConfig config) {
        HikariConfig hikariConfig = buildHikariConfig(config);
        this.dataSource = new HikariDataSource(hikariConfig);
        log.info("Pula połączeń HikariCP uruchomiona [maxPool={}, url={}]",
                config.getDbPoolMax(), maskPassword(config.getDbUrl()));
    }

    /**
     * Pobiera połączenie z puli. Wywołujący jest odpowiedzialny za zamknięcie
     * połączenia (najlepiej w bloku try-with-resources), co zwróci je do puli.
     *
     * @return aktywne połączenie z bazą danych
     * @throws SQLException gdy pula wyczerpana lub baza niedostępna
    */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Sprawdza czy pula jest otwarta i gotowa do działania.
     *
     * @return true jeśli HikariDataSource nie jest zamknięty
    */
    public boolean isRunning() {
        return dataSource != null && !dataSource.isClosed();
    }

    /** Zamyka pulę i wszystkie połączenia. Wywołać w shutdown hooku aplikacji. */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Pula połączeń HikariCP zamknięta");
        }
    }

    // -------------------------------------------------------------------------
    // Konfiguracja HikariCP
    // -------------------------------------------------------------------------
    private HikariConfig buildHikariConfig(AppConfig config) {
        HikariConfig hc = new HikariConfig();

        hc.setJdbcUrl(config.getDbUrl());

        String user = config.getDbUser();
        String pass = config.getDbPassword();
        if (user != null && !user.isBlank()) {
            hc.setUsername(user);
            hc.setPassword(pass);
        }

        hc.setMaximumPoolSize(config.getDbPoolMax());
        hc.setConnectionTimeout(config.getRaw("db.connection.timeout.ms").isBlank()
                ? 30_000
                : Long.parseLong(config.getRaw("db.connection.timeout.ms")));
        hc.setPoolName("EcoAlerterPool");

        // Optymalizacje specyficzne dla SQLite
        if (config.getDbUrl().startsWith("jdbc:sqlite:")) {
            applySQLiteOptimizations(hc);
        }

        return hc;
    }

    /**
     * Ustawia właściwości SQLite poprawiające wydajność i bezpieczeństwo danych.
     * WAL (Write-Ahead Logging) umożliwia równoległy odczyt i zapis.
    */
    private void applySQLiteOptimizations(HikariConfig hc) {
        hc.setMaximumPoolSize(1); // SQLite nie obsługuje prawdziwej wielowątkowości
        hc.addDataSourceProperty("journal_mode", "WAL");
        hc.addDataSourceProperty("synchronous",  "NORMAL");
        hc.addDataSourceProperty("foreign_keys", "ON");
        hc.addDataSourceProperty("busy_timeout", "5000");
        log.debug("Zastosowano optymalizacje SQLite (WAL, foreign_keys=ON)");
    }

    /** Maskuje hasło w URL do logowania (zastępuje treść po 'password=' gwiazdkami). */
    private String maskPassword(String url) {
        if (url == null) return "";
        return url.replaceAll("(?i)(password=)[^&;]+", "$1***");
    }
}