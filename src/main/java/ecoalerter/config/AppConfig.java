package ecoalerter.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ecoalerter.model.WarningLevel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Centralny punkt dostępu do konfiguracji aplikacji.
 *
 * <p>Ładuje {@code app.properties} w następującej kolejności priorytetów:
 * <ol>
 *   <li>Ścieżka podana przez argument {@code --config /ścieżka/do/pliku}</li>
 *   <li>Plik {@code app.properties} w katalogu roboczym aplikacji</li>
 *   <li>Wbudowane wartości domyślne z {@code resources/app.properties}</li>
 * </ol>
 *
 * <p>Singleton — jeden egzemplarz na aplikację, tworzony przez {@link #load()}.
 *
 * <p>Przykład użycia:
 * <pre>
 *     AppConfig config = AppConfig.load();
 *     // lub z niestandardową ścieżką:
 *     AppConfig config = AppConfig.load(Paths.get("/etc/ecoalerter/app.properties"));
 * </pre>
 */
public class AppConfig {

    private static final Logger log = LogManager.getLogger(AppConfig.class);

    /** Nazwa pliku konfiguracyjnego w katalogu roboczym i na classpath. */
    private static final String CONFIG_FILE_NAME = "app.properties";

    private final Properties props;

    // -------------------------------------------------------------------------
    // Statyczne metody fabryczne
    // -------------------------------------------------------------------------

    /**
     * Ładuje konfigurację z domyślnej lokalizacji.
     * Szuka {@code app.properties} w katalogu roboczym,
     * a jako fallback używa wbudowanych wartości domyślnych.
     */
    public static AppConfig load() {
        Path workDir = Paths.get(System.getProperty("user.dir"), CONFIG_FILE_NAME);
        return load(workDir);
    }

    /**
     * Ładuje konfigurację z podanej ścieżki.
     * Jeśli plik nie istnieje — wraca do wbudowanych wartości domyślnych.
     *
     * @param configPath ścieżka do pliku .properties
     */
    public static AppConfig load(Path configPath) {
        Properties defaults = loadDefaults();
        Properties merged   = new Properties(defaults);

        if (configPath != null && Files.exists(configPath)) {
            try (InputStream is = Files.newInputStream(configPath)) {
                merged.load(is);
                log.info("Załadowano konfigurację z: {}", configPath.toAbsolutePath());
            } catch (IOException e) {
                log.warn("Nie można odczytać pliku konfiguracyjnego '{}' — używam wartości domyślnych: {}",
                        configPath, e.getMessage());
            }
        } else {
            log.info("Plik '{}' nie istnieje — używam wartości domyślnych z classpath", configPath);
        }

        return new AppConfig(merged);
    }

    // -------------------------------------------------------------------------
    // Konstruktor (prywatny)
    // -------------------------------------------------------------------------

    private AppConfig(Properties props) {
        this.props = props;
        log.debug("AppConfig zainicjalizowany z {} kluczami", props.size());
    }

    // -------------------------------------------------------------------------
    // Persystencja
    // -------------------------------------------------------------------------

    /** Tryb zapisu danych: {@code FILE} lub {@code DATABASE}. */
    public PersistenceMode getPersistenceMode() {
        return PersistenceMode.fromString(get("persistence.mode"));
    }

    /** Format pliku przy trybie FILE: {@code JSON} lub {@code CSV}. */
    public String getStorageFileFormat() {
        return get("storage.file.format").toUpperCase();
    }

    /** Katalog zapisu plików danych (ścieżka z properties). */
    public String getStorageFileDir() {
        return get("storage.file.dir");
    }

    // -------------------------------------------------------------------------
    // Baza danych
    // -------------------------------------------------------------------------

    public String getDbUrl()      { return get("db.url"); }
    public String getDbUser()     { return get("db.user"); }
    public String getDbPassword() { return get("db.password"); }

    /** Maksymalna liczba połączeń w puli HikariCP. */
    public int getDbPoolMax() {
        return getInt("db.pool.max", 5);
    }

    // -------------------------------------------------------------------------
    // API IMGW
    // -------------------------------------------------------------------------

    public String getApiBaseUrl() {
        return get("api.imgw.base.url");
    }

    /** Timeout żądania HTTP w sekundach. */
    public int getApiTimeoutSeconds() {
        return getInt("api.timeout.seconds", 10);
    }

    /** Liczba prób ponowienia żądania przy błędach przejściowych. */
    public int getApiRetryCount() {
        return getInt("api.retry.count", 3);
    }

    // -------------------------------------------------------------------------
    // Scheduler
    // -------------------------------------------------------------------------

    /** Domyślny interwał odpytywania API w sekundach (nadpisywalny per stacja w GUI). */
    public int getSchedulerDefaultIntervalSeconds() {
        return getInt("scheduler.default.interval.seconds", 300);
    }

    // -------------------------------------------------------------------------
    // Ostrzeżenia
    // -------------------------------------------------------------------------

    public boolean isWarningsEnabled() {
        return getBool("warnings.enabled", true);
    }

    /** Minimalny poziom alertu do wyświetlania/zapisu. */
    public WarningLevel getWarningsFilterLevel() {
        String raw = get("warnings.filter.level");
        try {
            return WarningLevel.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Nieznany poziom ostrzeżeń '{}' — używam YELLOW", raw);
            return WarningLevel.YELLOW;
        }
    }

    // -------------------------------------------------------------------------
    // Logowanie
    // -------------------------------------------------------------------------

    public String getLogLevel()        { return get("log.level"); }
    public boolean isLogFileEnabled()  { return getBool("log.file.enabled", true); }
    public String getLogFileDir()      { return get("log.file.dir"); }

    // -------------------------------------------------------------------------
    // DataTypeConfig — zakres zbieranych danych
    // -------------------------------------------------------------------------

    /**
     * Buduje i zwraca obiekt {@link DataTypeConfig} na podstawie aktualnych properties.
     * Metoda tworzy nowy obiekt przy każdym wywołaniu (nie cachuje).
     */
    public DataTypeConfig getDataTypeConfig() {
        DataTypeConfig cfg = new DataTypeConfig();

        cfg.setMeteoEnabled(getBool("data.meteo.enabled", true));
        cfg.setHydroEnabled(getBool("data.hydro.enabled", true));
        cfg.setWarningsEnabled(isWarningsEnabled());

        cfg.setTemperatureEnabled(getBool("data.meteo.temperature", true));
        cfg.setWindEnabled(getBool("data.meteo.wind", true));
        cfg.setPrecipitationEnabled(getBool("data.meteo.precipitation", true));

        cfg.setWaterLevelEnabled(getBool("data.hydro.waterLevel", true));
        cfg.setWaterTemperatureEnabled(getBool("data.hydro.waterTemperature", true));

        cfg.setWarningMinLevel(getWarningsFilterLevel());

        return cfg;
    }

    // -------------------------------------------------------------------------
    // Dostęp do surowych wartości (dla modułów zewnętrznych)
    // -------------------------------------------------------------------------

    /**
     * Zwraca surową wartość właściwości lub pusty String jeśli nie istnieje.
     */
    public String getRaw(String key) {
        return props.getProperty(key, "");
    }

    /**
     * Ustawia właściwość w pamięci (nie zapisuje do pliku).
     * Używane przez SettingsPanel do live-reload wybranych wartości.
     */
    public void setRaw(String key, String value) {
        props.setProperty(key, value);
        log.debug("Konfiguracja zaktualizowana w pamięci: {} = {}", key, value);
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    /** Odczyt String z pustym stringiem jako fallback (nigdy null). */
    private String get(String key) {
        return props.getProperty(key, "").trim();
    }

    private int getInt(String key, int defaultValue) {
        String raw = get(key);
        if (raw.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("Nieprawidłowa wartość int dla klucza '{}' = '{}' — używam {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private boolean getBool(String key, boolean defaultValue) {
        String raw = get(key);
        if (raw.isEmpty()) return defaultValue;
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    /** Ładuje wbudowane wartości domyślne z classpath (resources/app.properties). */
    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream is = AppConfig.class
                .getClassLoader()
                .getResourceAsStream(CONFIG_FILE_NAME)) {
            if (is != null) {
                defaults.load(is);
                log.debug("Załadowano domyślny app.properties z classpath ({} kluczy)", defaults.size());
            } else {
                log.warn("Brak domyślnego app.properties na classpath — używam wartości zakodowanych");
                loadHardcodedDefaults(defaults);
            }
        } catch (IOException e) {
            log.error("Błąd odczytu domyślnego app.properties: {}", e.getMessage());
            loadHardcodedDefaults(defaults);
        }
        return defaults;
    }

    /** Ostatnia linia obrony — wartości zakodowane na stałe w kodzie. */
    private static void loadHardcodedDefaults(Properties p) {
        p.setProperty("persistence.mode",                    "FILE");
        p.setProperty("storage.file.format",                 "JSON");
        p.setProperty("storage.file.dir",                    "./data");
        p.setProperty("db.url",                              "jdbc:sqlite:./data/ecoalerter.db");
        p.setProperty("db.user",                             "");
        p.setProperty("db.password",                         "");
        p.setProperty("db.pool.max",                         "5");
        p.setProperty("api.imgw.base.url",                   "https://danepubliczne.imgw.pl/api/data");
        p.setProperty("api.timeout.seconds",                 "10");
        p.setProperty("api.retry.count",                     "3");
        p.setProperty("scheduler.default.interval.seconds",  "300");
        p.setProperty("warnings.enabled",                    "true");
        p.setProperty("warnings.filter.level",               "YELLOW");
        p.setProperty("log.level",                           "INFO");
        p.setProperty("log.file.enabled",                    "true");
        p.setProperty("log.file.dir",                        "./logs");
        p.setProperty("data.meteo.enabled",                  "true");
        p.setProperty("data.hydro.enabled",                  "true");
        p.setProperty("data.meteo.temperature",              "true");
        p.setProperty("data.meteo.wind",                     "true");
        p.setProperty("data.meteo.precipitation",            "true");
        p.setProperty("data.hydro.waterLevel",               "true");
        p.setProperty("data.hydro.waterTemperature",         "true");
    }
}