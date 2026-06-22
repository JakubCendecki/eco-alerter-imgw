package ecoalerter.util;

import org.apache.logging.log4j.Logger;

import javax.swing.ImageIcon;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Narzędzie do bezpiecznego wczytywania ikon z classpath (src/main/resources/icons).
 *
 * Wszystkie metody są odporne na brakujące lub uszkodzone pliki — zamiast
 * rzucać wyjątek, logują ostrzeżenie i zwracają null. GUI musi sam zdecydować
 * co zrobić z brakującą ikoną (najczęściej: po prostu nie ustawiać jej,
 * pozostając przy domyślnym wyglądzie komponentu Swing).
 *
 * Wyniki są cache'owane w pamięci — powtórne wczytanie tej samej ikony
 * (np. tej samej ikony w wielu zakładkach) nie czyta pliku z dysku ponownie.
 */
public final class IconLoader {

    private static final Logger log = AppLogger.get(IconLoader.class);

    /** Katalog ikon na classpath, względny wobec korzenia resources. */
    private static final String ICONS_DIR = "/icons/";

    private static final Map<String, ImageIcon> CACHE = new HashMap<>();

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Wczytuje ikonę z katalogu icons/ na classpath.
     *
     * @param fileName nazwa pliku z rozszerzeniem, np. "app-icon.png"
     * @return wczytana ikona, lub null gdy plik nie istnieje albo jest uszkodzony
     */
    public static ImageIcon load(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;

        return CACHE.computeIfAbsent(fileName, IconLoader::loadFromClasspath);
    }

    /**
     * Wczytuje ikonę i skaluje ją do podanego rozmiaru (kwadrat).
     * Przydatne dla ikon zakładek, które powinny mieć stały, niewielki rozmiar
     * niezależnie od rozdzielczości oryginalnego pliku.
     *
     * @param fileName nazwa pliku z rozszerzeniem
     * @param size     docelowa szerokość i wysokość w pikselach
     * @return przeskalowana ikona, lub null gdy oryginał nie został wczytany
     */
    public static ImageIcon loadScaled(String fileName, int size) {
        ImageIcon original = load(fileName);
        if (original == null) return null;

        String cacheKey = fileName + "@" + size;
        return CACHE.computeIfAbsent(cacheKey, k -> {
            java.awt.Image scaled = original.getImage()
                    .getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        });
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    private static ImageIcon loadFromClasspath(String fileName) {
        String path = ICONS_DIR + fileName;
        URL url = IconLoader.class.getResource(path);

        if (url == null) {
            log.warn("Nie znaleziono ikony na classpath: {} — komponent użyje domyślnego wyglądu", path);
            return null;
        }

        try {
            ImageIcon icon = new ImageIcon(url);
            log.debug("Wczytano ikonę: {}", path);
            return icon;
        } catch (Exception e) {
            log.warn("Błąd wczytywania ikony {}: {}", path, e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Konstruktor prywatny
    // -------------------------------------------------------------------------

    private IconLoader() {
        // klasa narzędziowa — brak instancji
    }
}