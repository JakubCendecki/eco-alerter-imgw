package ecoalerter.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Narzędzia do formatowania, parsowania i wyświetlania dat oraz czasu.
 *
 * <p>Klasa dostarcza zestaw predefiniowanych formaterów zgodnych z formatami
 * używanymi przez API IMGW-PIB oraz na potrzeby wewnętrzne aplikacji
 * (wyświetlanie w GUI, zapis do pliku, zapis do bazy danych).
 *
 * <p>Wszystkie metody są statyczne i bezpieczne wątkowo —
 * {@link DateTimeFormatter} jest immutable w standardzie Java.
 *
 * <p>Przykład użycia:
 * <pre>
 *     String display = DateTimeUtil.toDisplayString(data.getTimestamp());
 *     // → "14.06.2024 12:30"
 *
 *     Optional&lt;LocalDateTime&gt; parsed = DateTimeUtil.parse("2024-06-14 12");
 *     // → Optional[2024-06-14T12:00]
 * </pre>
 */
public final class DateTimeUtil {

    private static final Logger log = LogManager.getLogger(DateTimeUtil.class);

    // -------------------------------------------------------------------------
    // Formaty wewnętrzne aplikacji
    // -------------------------------------------------------------------------

    /**
     * Format do zapisu w bazie danych i plikach JSON.
     * ISO-like, sortowalny leksykograficznie: {@code yyyy-MM-dd HH:mm:ss}.
     */
    public static final DateTimeFormatter DB_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Format wyświetlania w GUI — czytelny dla użytkownika: {@code dd.MM.yyyy HH:mm}.
     */
    public static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * Format skrócony — sama godzina, używany w tabelach: {@code HH:mm:ss}.
     */
    public static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Format daty bez czasu — do nazw plików i grupowania: {@code yyyy-MM-dd}.
     */
    public static final DateTimeFormatter DATE_ONLY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Format sygnatury czasowej do nazw plików (bez znaków niedozwolonych):
     * {@code yyyyMMdd_HHmmss}.
     */
    public static final DateTimeFormatter FILE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // -------------------------------------------------------------------------
    // Formaty API IMGW
    // -------------------------------------------------------------------------

    /**
     * Format daty i godziny z API IMGW — data i godzina podane oddzielnie:
     * {@code yyyy-MM-dd HH}.
     */
    public static final DateTimeFormatter IMGW_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

    /**
     * Format daty i czasu ostrzeżeń IMGW: {@code yyyy-MM-dd HH:mm}.
     */
    public static final DateTimeFormatter IMGW_WARNING_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // -------------------------------------------------------------------------
    // Formatowanie
    // -------------------------------------------------------------------------

    /**
     * Formatuje {@link LocalDateTime} do postaci czytelnej dla użytkownika
     * (format {@link #DISPLAY_FORMAT}).
     *
     * @param dateTime data i czas do sformatowania; {@code null} zwraca {@code "—"}
     * @return sformatowany string lub {@code "—"} dla wartości null
     */
    public static String toDisplayString(LocalDateTime dateTime) {
        if (dateTime == null) return "—";
        return dateTime.format(DISPLAY_FORMAT);
    }

    /**
     * Formatuje {@link LocalDateTime} do postaci używanej w bazie danych
     * i plikach JSON (format {@link #DB_FORMAT}).
     *
     * @param dateTime data i czas do sformatowania; {@code null} zwraca pusty string
     * @return sformatowany string lub {@code ""} dla wartości null
     */
    public static String toDbString(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        return dateTime.format(DB_FORMAT);
    }

    /**
     * Formatuje {@link LocalDateTime} do bezpiecznej nazwy pliku
     * (format {@link #FILE_TIMESTAMP_FORMAT}).
     *
     * @param dateTime data i czas; {@code null} używa aktualnego czasu
     * @return string w formacie {@code yyyyMMdd_HHmmss}
     */
    public static String toFileTimestamp(LocalDateTime dateTime) {
        LocalDateTime dt = dateTime != null ? dateTime : LocalDateTime.now();
        return dt.format(FILE_TIMESTAMP_FORMAT);
    }

    /**
     * Formatuje {@link LocalDateTime} do samej daty (format {@link #DATE_ONLY_FORMAT}).
     *
     * @param dateTime data i czas; {@code null} zwraca {@code "—"}
     * @return string w formacie {@code yyyy-MM-dd} lub {@code "—"}
     */
    public static String toDateString(LocalDateTime dateTime) {
        if (dateTime == null) return "—";
        return dateTime.format(DATE_ONLY_FORMAT);
    }

    /**
     * Formatuje {@link LocalDateTime} do samej godziny (format {@link #TIME_FORMAT}).
     *
     * @param dateTime data i czas; {@code null} zwraca {@code "—"}
     * @return string w formacie {@code HH:mm:ss} lub {@code "—"}
     */
    public static String toTimeString(LocalDateTime dateTime) {
        if (dateTime == null) return "—";
        return dateTime.format(TIME_FORMAT);
    }

    // -------------------------------------------------------------------------
    // Parsowanie
    // -------------------------------------------------------------------------

    /**
     * Parsuje string do {@link LocalDateTime} próbując kolejno wszystkich
     * znanych formatów aplikacji.
     *
     * <p>Obsługiwane formaty (w kolejności prób):
     * {@link #DB_FORMAT}, {@link #IMGW_DATETIME_FORMAT}, {@link #IMGW_WARNING_FORMAT},
     * {@link #DISPLAY_FORMAT}, {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME}.
     *
     * @param raw string do sparsowania; może być {@code null} lub pusty
     * @return {@link Optional} z wynikiem lub {@code empty()} przy niepowodzeniu
     */
    public static Optional<LocalDateTime> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        String trimmed = raw.trim();
        DateTimeFormatter[] formats = {
                DB_FORMAT,
                IMGW_DATETIME_FORMAT,
                IMGW_WARNING_FORMAT,
                DISPLAY_FORMAT,
                DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        for (DateTimeFormatter fmt : formats) {
            try {
                return Optional.of(LocalDateTime.parse(trimmed, fmt));
            } catch (DateTimeParseException ignored) {
                // próba kolejnego formatu
            }
        }

        log.warn("Nie można sparsować daty: '{}'", raw);
        return Optional.empty();
    }

    /**
     * Parsuje datę i godzinę podane oddzielnie (format API IMGW).
     * Normalizuje godzinę — akceptuje zarówno {@code "6"} jak i {@code "06"}.
     *
     * @param date string daty w formacie {@code yyyy-MM-dd}
     * @param hour string godziny (jedno- lub dwucyfrowy)
     * @return sparsowany {@link LocalDateTime} lub aktualny czas przy błędzie
     */
    public static LocalDateTime parseImgwDateTime(String date, String hour) {
        if (date == null || hour == null) {
            log.debug("Brak daty lub godziny IMGW — używam LocalDateTime.now()");
            return LocalDateTime.now();
        }
        try {
            String paddedHour = hour.trim().length() == 1 ? "0" + hour.trim() : hour.trim();
            return LocalDateTime.parse(date.trim() + " " + paddedHour, IMGW_DATETIME_FORMAT);
        } catch (DateTimeParseException e) {
            log.debug("Błąd parsowania daty IMGW [{} {}]: {}", date, hour, e.getMessage());
            return LocalDateTime.now();
        }
    }

    // -------------------------------------------------------------------------
    // Obliczenia na czasie
    // -------------------------------------------------------------------------

    /**
     * Oblicza czytelny opis czasu, który minął od podanej daty
     * (np. {@code "5 minut temu"}, {@code "2 godziny temu"}).
     *
     * @param from punkt w czasie od którego liczymy; {@code null} zwraca {@code "nieznany"}
     * @return czytelny string w języku polskim
     */
    public static String timeAgo(LocalDateTime from) {
        if (from == null) return "nieznany";

        Duration diff = Duration.between(from, LocalDateTime.now());
        long seconds = Math.abs(diff.getSeconds());

        if (seconds < 60)   return "przed chwilą";
        if (seconds < 3600) return (seconds / 60) + " min temu";
        if (seconds < 86400) return (seconds / 3600) + " godz. temu";
        return (seconds / 86400) + " dni temu";
    }

    /**
     * Sprawdza czy podany czas jest nadal ważny (tj. {@code validUntil} jest w przyszłości).
     *
     * @param validUntil czas wygaśnięcia; {@code null} oznacza bezterminowy (zawsze ważny)
     * @return {@code true} jeśli aktualny czas jest przed {@code validUntil}
     */
    public static boolean isStillValid(LocalDateTime validUntil) {
        if (validUntil == null) return true;
        return LocalDateTime.now().isBefore(validUntil);
    }

    /**
     * Zwraca aktualny czas sformatowany dla pola "ostatnia synchronizacja" w GUI.
     *
     * @return string w formacie {@link #DISPLAY_FORMAT}
     */
    public static String nowDisplay() {
        return LocalDateTime.now().format(DISPLAY_FORMAT);
    }

    /**
     * Zwraca aktualną datę jako string do nazw plików.
     *
     * @return string w formacie {@link #DATE_ONLY_FORMAT}
     */
    public static String todayForFilename() {
        return LocalDateTime.now().format(DATE_ONLY_FORMAT);
    }

    // -------------------------------------------------------------------------
    // Konstruktor prywatny
    // -------------------------------------------------------------------------

    private DateTimeUtil() {
        // klasa narzędziowa — brak instancji
    }
}