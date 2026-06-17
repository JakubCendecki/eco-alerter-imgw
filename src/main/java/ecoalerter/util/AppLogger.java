package ecoalerter.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Centralne narzędzie logowania dla aplikacji EcoAlerter IMGW.
 *
 * Odpowiada za:
 *   Fabrykę loggerów z predefiniowanym nazewnictwem kategorii
 *   Zmianę poziomu logowania w trakcie działania aplikacji (bez restartu)
 *   Ustrukturyzowane zdarzenia audytowe (start/stop aplikacji, zmiana konfiguracji)
 *   Logowanie zbiorczych podsumowań cykli zbierania danych
 *
 * Klasy aplikacji powinny tworzyć logger przez:
 *     private static final Logger log = AppLogger.get(MyClass.class);
 *
 * Zmiana poziomu logowania w GUI (SettingsPanel):
 *     AppLogger.setRootLevel("DEBUG");
 *     AppLogger.setRootLevel("INFO");
 */
public final class AppLogger {
    /** Logger audytowy — osobna kategoria dla zdarzeń systemowych. */
    private static final Logger AUDIT = LogManager.getLogger("ecoalerter.AUDIT");

    /** Logger dla komunikatów zbiorczych schedulera. */
    private static final Logger CYCLE = LogManager.getLogger("ecoalerter.CYCLE");

    /**
     * Zwraca logger Log4j2 dla podanej klasy.
     * Skrót zastępujący bezpośrednie wywołanie {@link LogManager#getLogger(Class)}.
     *
     * @param clazz klasa, dla której tworzony jest logger
     * @return logger powiązany z klasą
    */
    public static Logger get(Class<?> clazz) {
        return LogManager.getLogger(clazz);
    }

    /**
     * Zwraca logger Log4j2 dla podanej nazwy kategorii.
     * Używany do tworzenia loggerów dla grup funkcjonalnych.
     *
     * @param name nazwa kategorii (np. {@code "ecoalerter.api"})
     * @return logger powiązany z nazwą
    */
    public static Logger get(String name) {
        return LogManager.getLogger(name);
    }

    /**
     * Loguje zdarzenie startu aplikacji z informacjami o środowisku.
     * Wywoływać raz, na początku metody {@code main()}.
     *
     * @param version wersja aplikacji (np. {@code "1.0.0"})
    */
    public static void logAppStart(String version) {
        AUDIT.info("========================================================");
        AUDIT.info("  EcoAlerter IMGW v{} — START", version);
        AUDIT.info("  Java:    {} ({})", System.getProperty("java.version"),
                System.getProperty("java.vendor"));
        AUDIT.info("  OS:      {} {} ({})", System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch"));
        AUDIT.info("  Katalog: {}", System.getProperty("user.dir"));
        AUDIT.info("========================================================");
    }

    /**
     * Loguje zdarzenie zatrzymania aplikacji.
     * Wywoływać w bloku shutdown hook lub na końcu {@code main()}.
     *
     * @param reason powód zatrzymania (np. {@code "żądanie użytkownika"}, {@code "błąd krytyczny"})
    */
    public static void logAppStop(String reason) {
        AUDIT.info("========================================================");
        AUDIT.info("  EcoAlerter IMGW — STOP [{}]", reason);
        AUDIT.info("========================================================");
    }

    /**
     * Loguje zmianę konfiguracji dokonaną przez użytkownika w GUI.
     *
     * @param parameter nazwa zmienianego parametru
     * @param oldValue  stara wartość
     * @param newValue  nowa wartość
    */
    public static void logConfigChange(String parameter, String oldValue, String newValue) {
        AUDIT.info("Zmiana konfiguracji: {} = '{}' → '{}'", parameter, oldValue, newValue);
    }

    /**
     * Loguje dodanie lub usunięcie stacji pomiarowej przez użytkownika.
     *
     * @param action    {@code "DODANO"} lub {@code "USUNIETO"}
     * @param stationId identyfikator stacji
     * @param stationName nazwa stacji
     * @param type      typ stacji ({@code "METEO"} / {@code "HYDRO"})
    */
    public static void logStationChange(String action, String stationId,
                                        String stationName, String type) {
        AUDIT.info("Stacja {} | {} | {} | {}", action, type, stationId, stationName);
    }

    /**
     * Loguje podsumowanie zakończonego cyklu pobierania danych.
     *
     * @param stationId    identyfikator stacji
     * @param stationType  typ ({@code "METEO"} / {@code "HYDRO"})
     * @param success      czy pobieranie zakończyło się sukcesem
     * @param durationMs   czas trwania cyklu w milisekundach
     * @param errorMessage komunikat błędu lub {@code null} przy sukcesie
    */
    public static void logFetchCycle(String stationId, String stationType,
                                     boolean success, long durationMs,
                                     String errorMessage) {
        if (success) {
            CYCLE.info("[OK]  {} {} | {}ms", stationType, stationId, durationMs);
        } else {
            CYCLE.warn("[ERR] {} {} | {}ms | {}", stationType, stationId, durationMs, errorMessage);
        }
    }

    /**
     * Loguje wykrycie nowego ostrzeżenia IMGW.
     *
     * @param level   poziom alertu ({@code "YELLOW"} / {@code "ORANGE"} / {@code "RED"})
     * @param type    typ ({@code "METEO"} / {@code "HYDRO"})
     * @param message treść ostrzeżenia
    */
    public static void logWarningDetected(String level, String type, String message) {
        if ("RED".equalsIgnoreCase(level)) {
            AUDIT.error("OSTRZEŻENIE {} [{}]: {}", level, type, message);
        } else if ("ORANGE".equalsIgnoreCase(level)) {
            AUDIT.warn("OSTRZEŻENIE {} [{}]: {}", level, type, message);
        } else {
            AUDIT.info("OSTRZEŻENIE {} [{}]: {}", level, type, message);
        }
    }

    /**
     * Zmienia globalny (root) poziom logowania bez restartu aplikacji.
     * Wywoływany z panelu ustawień GUI.
     *
     * <p>Dozwolone wartości: {@code TRACE}, {@code DEBUG}, {@code INFO},
     * {@code WARN}, {@code ERROR}, {@code FATAL}.
     *
     * @param levelName nazwa poziomu logowania (wielkość liter bez znaczenia)
     * @throws IllegalArgumentException gdy podano nieznany poziom
    */
    public static void setRootLevel(String levelName) {
        Level level = Level.getLevel(levelName.toUpperCase());
        if (level == null) {
            throw new IllegalArgumentException("Nieznany poziom logowania: " + levelName);
        }
        setLoggerLevel(LogManager.ROOT_LOGGER_NAME, level);
        AUDIT.info("Poziom logowania root zmieniony na: {}", level);
    }

    /**
     * Zmienia poziom logowania dla konkretnej kategorii (pakietu lub klasy).
     *
     * @param loggerName nazwa kategorii (np. {@code "ecoalerter.api"})
     * @param levelName  nazwa poziomu logowania
    */
    public static void setLevel(String loggerName, String levelName) {
        Level level = Level.getLevel(levelName.toUpperCase());
        if (level == null) {
            throw new IllegalArgumentException("Nieznany poziom logowania: " + levelName);
        }
        setLoggerLevel(loggerName, level);
        AUDIT.info("Poziom logowania '{}' zmieniony na: {}", loggerName, level);
    }

    /**
     * Zwraca aktualny poziom logowania dla root loggera.
     *
     * @return nazwa poziomu (np. {@code "INFO"})
    */
    public static String getRootLevel() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        return ctx.getConfiguration()
                  .getLoggerConfig(LogManager.ROOT_LOGGER_NAME)
                  .getLevel()
                  .name();
    }

    /**
     * Stosuje zmianę poziomu do konfiguracji Log4j2 w runtime.
     *
     * @param loggerName nazwa loggera
     * @param level      nowy poziom
    */
    private static void setLoggerLevel(String loggerName, Level level) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        LoggerConfig loggerConfig = config.getLoggerConfig(loggerName);

        // Jeśli nie ma dedykowanego LoggerConfig dla tej nazwy — modyfikujemy root
        if (!loggerConfig.getName().equals(loggerName)) {
            loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
        }

        loggerConfig.setLevel(level);
        ctx.updateLoggers();
    }
    
    private AppLogger() { }
}