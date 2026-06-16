package pl.ecoalerter.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Narzędzie do wieloplatformowej obsługi ścieżek dostępu do zasobów aplikacji.
 *
 * Wszystkie ścieżki budowane są przez {@link java.nio.file.Paths} — bez
 * hardcodowanych separatorów {@code /} czy {@code \}. Dzięki temu kod działa
 * identycznie na Windows, Linux i macOS.
 *
 * Hierarchia katalogów aplikacji:
 * [appHome]/
 *   ├── data/
 *   │   ├── meteo/
 *   │   └── hydro/
 *   ├── logs/
 *   └── app.properties
 *
 * Domyślnie {@code appHome} to katalog roboczy JVM ({@code user.dir}).
 * Można go nadpisać przez właściwość systemową {@code ecoalerter.home}.
 *
 * Przykład użycia:
 *     PathResolver resolver = new PathResolver(config.getStorageFileDir(), config.getLogFileDir());
 *     Path stationFile = resolver.resolveMeteoFile("12200");
*/
public class PathResolver {

    private static final Logger log = LogManager.getLogger(PathResolver.class);

    /** Właściwość systemowa pozwalająca nadpisać katalog domowy aplikacji. */
    public static final String HOME_PROPERTY = "ecoalerter.home";

    private final Path appHome;
    private final Path dataDir;
    private final Path logDir;

    /**
     * Tworzy resolver z katalogami z {@code app.properties}.
     *
     * @param storageDirRaw wartość {@code storage.file.dir} z konfiguracji
     * @param logDirRaw     wartość {@code log.file.dir} z konfiguracji
    */
    public PathResolver(String storageDirRaw, String logDirRaw) {
        this.appHome = resolveAppHome();
        this.dataDir = resolveDir(storageDirRaw, "data");
        this.logDir  = resolveDir(logDirRaw,     "logs");
        log.info("PathResolver: appHome={}, data={}, logs={}", appHome, dataDir, logDir);
    }

    /** Katalog domowy aplikacji (bazowy dla wszystkich ścieżek względnych). */
    public Path getAppHome() { return appHome; }

    /** Katalog danych ({@code storage.file.dir}). */
    public Path getDataDir() { return dataDir; }

    /** Katalog logów ({@code log.file.dir}). */
    public Path getLogDir()  { return logDir; }

    /** Podkatalog danych meteo: {@code <dataDir>/meteo/}. */
    public Path getMeteoDir() {
        return dataDir.resolve("meteo");
    }

    /** Podkatalog danych hydro: {@code <dataDir>/hydro/}. */
    public Path getHydroDir() {
        return dataDir.resolve("hydro");
    }

    /** Podkatalog ostrzeżeń: {@code <dataDir>/warnings/}. */
    public Path getWarningsDir() {
        return dataDir.resolve("warnings");
    }

    /**
     * Plik danych meteo dla konkretnej stacji.
     * Nazwa pliku: {@code <stationId>_<stationName>.<extension>}
     *
     * @param stationId   ID stacji (np. "12200")
     * @param stationName nazwa stacji (np. "WARSZAWA")
     * @param extension   rozszerzenie bez kropki: "json" lub "csv"
    */
    public Path resolveMeteoFile(String stationId, String stationName, String extension) {
        String filename = sanitizeFilename(stationId + "_" + stationName) + "." + extension.toLowerCase();
        return getMeteoDir().resolve(filename);
    }

    /** Plik danych hydro dla konkretnej stacji. */
    public Path resolveHydroFile(String stationId, String stationName, String extension) {
        String filename = sanitizeFilename(stationId + "_" + stationName) + "." + extension.toLowerCase();
        return getHydroDir().resolve(filename);
    }

    /** Plik ostrzeżeń z datą w nazwie: {@code warnings_YYYY-MM-DD.<extension>} */
    public Path resolveWarningsFile(String date, String extension) {
        String filename = "warnings_" + sanitizeFilename(date) + "." + extension.toLowerCase();
        return getWarningsDir().resolve(filename);
    }

    /** Plik konfiguracji stacji: {@code <dataDir>/stations.json}. */
    public Path getStationsConfigFile() {
        return dataDir.resolve("stations.json");
    }

    /** Plik konfiguracji harmonogramu: {@code <dataDir>/schedule.json}. */
    public Path getScheduleConfigFile() {
        return dataDir.resolve("schedule.json");
    }

    /**
     * Tworzy wszystkie wymagane katalogi aplikacji (jeśli nie istnieją).
     * Wywołać przy starcie aplikacji.
     *
     * @throws IOException gdy nie uda się utworzyć któregoś z katalogów
    */
    public void createRequiredDirectories() throws IOException {
        createDir(dataDir);
        createDir(getMeteoDir());
        createDir(getHydroDir());
        createDir(getWarningsDir());
        createDir(logDir);
        log.info("Wszystkie wymagane katalogi gotowe");
    }

    private void createDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.debug("Utworzono katalog: {}", dir);
        }
    }

    /**
     * Wyznacza katalog domowy aplikacji.
     * Kolejność priorytetów:
     *   >Właściwość systemowa {@code ecoalerter.home}
     *   Katalog roboczy JVM ({@code user.dir})
    */
    private static Path resolveAppHome() {
        String homeOverride = System.getProperty(HOME_PROPERTY);
        if (homeOverride != null && !homeOverride.isBlank()) {
            Path custom = Paths.get(homeOverride).toAbsolutePath().normalize();
            log.info("Używam katalogu domowego z właściwości systemowej: {}", custom);
            return custom;
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Rozwiązuje ścieżkę katalogu z konfiguracji.
     * Ścieżki względne są rozwiązywane względem {@code appHome}.
     *
     * @param rawPath     wartość z properties (może być null/pusta)
     * @param fallbackName nazwa katalogu fallback (np. "data")
    */
    private Path resolveDir(String rawPath, String fallbackName) {
        if (rawPath == null || rawPath.isBlank()) {
            return appHome.resolve(fallbackName).normalize();
        }
        Path p = Paths.get(rawPath);
        return p.isAbsolute()
                ? p.normalize()
                : appHome.resolve(p).normalize();
    }

    /**
     * Usuwa z nazwy pliku znaki niedozwolone na wszystkich platformach.
     * Zastępuje: {@code \ / : * ? " < > | spacja} podkreślnikiem.
    */
    public static String sanitizeFilename(String raw) {
        if (raw == null) return "unknown";
        return raw.trim()
                  .replaceAll("[\\\\/:*?\"<>|\\s]", "_")
                  .replaceAll("_+", "_");
    }

    /**
     * Sprawdza czy ścieżka jest dostępna do zapisu.
     *
     * @param path katalog lub plik do sprawdzenia
     * @return true jeśli istnieje i można do niego pisać
    */
    public static boolean isWritable(Path path) {
        return Files.exists(path) && Files.isWritable(path);
    }
}