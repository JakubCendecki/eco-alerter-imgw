package ecoalerter.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ecoalerter.model.WarningLevel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Centralny punkt dostępu do konfiguracji aplikacji.
 *
 * Ładuje app.properties w następującej kolejności priorytetów:
 * 1. Ścieżka podana przez argument --config /ścieżka/do/pliku
 * 2. Plik src/main/resources/user/app.properties (zapisywalna kopia z bieżącymi
 *    ustawieniami — tworzona automatycznie przy pierwszym zapisie)
 * 3. Wbudowane wartości domyślne z resources/app.properties na classpath
 *
 * Bundlowany resources/app.properties (na classpath, w katalogu głównym
 * src/main/resources) NIE jest nigdy modyfikowany przez aplikację — pełni
 * rolę stałego "backupu"/fabrycznych ustawień, do których można zawsze
 * wrócić. Wszystkie realne zmiany z GUI trafiają do oddzielnego pliku
 * w podkatalogu user/, fizycznie odseparowanego od oryginału.
 *
 * Każda zmiana przez setRaw() lub resetToDefaults() jest natychmiast
 * zapisywana na dysk (do configPath zapamiętanego przy load()) — bez tego
 * zmiany z GUI istniałyby tylko w pamięci aktualnej sesji i ginęłyby
 * po restarcie aplikacji, bo load() i tak na nowo czyta plik z dysku.
 * Zapisywane są tylko wartości różniące się od domyślnych (Properties.store()
 * pisze jedynie jawnie ustawione wpisy, nie warstwę "defaults") — czyli plik
 * user/app.properties zawiera dokładnie to, co zostało zmienione względem
 * oryginału, nie pełną kopię.
 *
 * Singleton — jeden egzemplarz na aplikację, tworzony przez load().
 */
public class AppConfig {

    private static final Logger log = LogManager.getLogger(AppConfig.class);

    /** Nazwa pliku konfiguracyjnego — taka sama dla backupu i kopii zapisywalnej. */
    private static final String CONFIG_FILE_NAME = "app.properties";

    /**
     * Ścieżka (względna wobec katalogu roboczego) do zapisywalnej kopii
     * konfiguracji — oddzielnej od bundlowanego resources/app.properties,
     * żeby ten ostatni zawsze pozostawał nienaruszonym backupem.
     */
    private static final Path USER_CONFIG_RELATIVE_PATH =
            Paths.get("src", "main", "resources", CONFIG_FILE_NAME);

    private final Properties props;
    private final Path       configPath;

    // -------------------------------------------------------------------------
    // Statyczne metody fabryczne
    // -------------------------------------------------------------------------

    /**
     * Ładuje konfigurację z domyślnej, zapisywalnej lokalizacji
     * (src/main/resources/user/app.properties względem katalogu roboczego).
     * Jeśli ten plik jeszcze nie istnieje (pierwsze uruchomienie albo świeże
     * środowisko), aplikacja startuje na wbudowanych wartościach domyślnych,
     * a plik zostanie utworzony automatycznie przy pierwszym zapisie ustawień.
     */
    public static AppConfig load() {
        Path userConfigPath = Paths.get(System.getProperty("user.dir"))
                .resolve(USER_CONFIG_RELATIVE_PATH);
        return load(userConfigPath);
    }

    /**
     * Ładuje konfigurację z podanej ścieżki.
     * Jeśli plik nie istnieje — wraca do wbudowanych wartości domyślnych.
     * Ścieżka jest zapamiętywana — kolejne zmiany przez setRaw() będą
     * zapisywane właśnie do tego pliku.
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
            log.info("Plik '{}' nie istnieje jeszcze — startuję na wartościach domyślnych " +
                     "z classpath (zostanie utworzony automatycznie przy pierwszym zapisie)", configPath);
        }

        return new AppConfig(merged, configPath);
    }

    // -------------------------------------------------------------------------
    // Konstruktor (prywatny)
    // -------------------------------------------------------------------------

    private AppConfig(Properties props, Path configPath) {
        this.props      = props;
        this.configPath = configPath;
        log.debug("AppConfig zainicjalizowany z {} kluczami, zapis do: {}", props.size(), configPath);
    }

    // -------------------------------------------------------------------------
    // Persystencja
    // -------------------------------------------------------------------------

    /** Tryb zapisu danych: {@code FILE} lub {@code DATABASE}. */
    public PersistenceMode getPersistenceMode() {
        return PersistenceMode.fromString(get("persistence.mode"));
    }

    /** Katalog zapisu plików danych (ścieżka z properties). */
    public String getStorageFileDir() {
        return get("storage.file.dir");
    }

    // -------------------------------------------------------------------------
    // Baza danych
    // -------------------------------------------------------------------------

    /**
     * Zwraca URL JDBC do połączenia z bazą danych.
     *
     * Jeśli z jakiegokolwiek powodu wartość okaże się pusta (np. nieaktualny
     * build classpath bez najnowszego app.properties, błąd w pliku konfiguracyjnym),
     * zwracamy zakodowany fallback do lokalnego SQLite — bez tego HikariCP
     * rzuca kryptyczny "dataSource or dataSourceClassName or jdbcUrl is required"
     * zamiast jasno wskazać przyczynę.
     */
    public String getDbUrl() {
        String url = get("db.url");
        if (url.isEmpty()) {
            log.warn("db.url jest puste w konfiguracji (sprawdź czy app.properties na classpath " +
                     "jest aktualny — np. po 'mvn clean package') — używam awaryjnego SQLite: " +
                     "jdbc:sqlite:./data/ecoalerter.db");
            return "jdbc:sqlite:./data/ecoalerter.db";
        }
        return url;
    }

    public String getDbUser()     { return get("db.user"); }
    public String getDbPassword() { return get("db.password"); }

    /** Maksymalna liczba połączeń w puli HikariCP. */
    public int getDbPoolMax() {
        return getInt("db.pool.max", 5);
    }

    // -------------------------------------------------------------------------
    // API IMGW
    // -------------------------------------------------------------------------

    /**
     * Zwraca bazowy URL API IMGW. Tak jak getDbUrl() — awaryjny fallback
     * na wypadek pustej wartości z konfiguracji, bo bez tego URL-a aplikacja
     * nie może wykonać żadnego zapytania do API.
     */
    public String getApiBaseUrl() {
        String url = get("api.imgw.base.url");
        if (url.isEmpty()) {
            log.warn("api.imgw.base.url jest puste w konfiguracji — używam awaryjnego: " +
                     "https://danepubliczne.imgw.pl/api/data");
            return "https://danepubliczne.imgw.pl/api/data";
        }
        return url;
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
        cfg.setHydroPhenomenaEnabled(getBool("data.hydro.phenomena", true));

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
     * Ustawia właściwość i natychmiast zapisuje całą konfigurację na dysk.
     * Bez zapisu na dysk zmiana istniałaby tylko w pamięci aktualnej sesji
     * i zostałaby utracona przy następnym starcie aplikacji, ponieważ load()
     * zawsze na nowo czyta plik z dysku.
     *
     * @param key   nazwa właściwości (np. "persistence.mode")
     * @param value nowa wartość jako String
     */
    public void setRaw(String key, String value) {
        props.setProperty(key, value);
        log.debug("Konfiguracja zaktualizowana: {} = {}", key, value);
        save();
    }

    /**
     * Resetuje wszystkie ustawienia do wartości domyślnych (wbudowanych
     * lub z classpath app.properties) i natychmiast zapisuje ten stan na dysk —
     * inaczej restart aplikacji wczytałby z powrotem stare, nadpisane wartości
     * z wciąż istniejącego na dysku pliku konfiguracyjnego.
     *
     * props zostało skonstruowane jako new Properties(defaults) — wywołanie
     * clear() usuwa tylko jawnie ustawione wpisy, dzięki czemu każde kolejne
     * getProperty() automatycznie spada na warstwę domyślną.
     */
    public void resetToDefaults() {
        props.clear();
        log.info("Konfiguracja zresetowana do wartości domyślnych");
        save();
    }

    /**
     * Zapisuje aktualną konfigurację do pliku, z którego została wczytana
     * (configPath zapamiętane przy load()). Zapisywane są tylko jawnie
     * nadpisane wartości (nie wbudowane defaulty) — Properties.store()
     * naturalnie ignoruje warstwę "defaults" przekazaną w konstruktorze,
     * co jest tu zachowaniem zamierzonym: plik na dysku zawiera wyłącznie
     * to, co różni się od wartości domyślnych.
     *
     * Błąd zapisu jest logowany, ale nie przerywa działania aplikacji —
     * zmiana zostaje przynajmniej zastosowana w pamięci do końca sesji.
     */
    public void save() {
        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream os = Files.newOutputStream(configPath)) {
                props.store(os, "EcoAlerter IMGW — zapisane automatycznie przez aplikację");
            }

            log.debug("Konfiguracja zapisana do: {}", configPath.toAbsolutePath());

        } catch (IOException e) {
            log.error("Nie udało się zapisać konfiguracji do {}: {}",
                    configPath, e.getMessage());
        }
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
        p.setProperty("data.hydro.phenomena",                "true");
    }
}