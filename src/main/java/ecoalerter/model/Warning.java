package ecoalerter.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Model ostrzeżenia meteorologicznego lub hydrologicznego IMGW-PIB.
 *
 * Ostrzeżenia są pobierane z endpointów:
 * {@code /api/data/warnings/meteo} — alerty pogodowe
 * {@code /api/data/warnings/hydro} — alerty hydrologiczne
 *
 * Pole {@link #stationId} może być {@code null} gdy ostrzeżenie
 * dotyczy całego regionu, a nie konkretnej stacji.
 *
 * Poziomy alertów reprezentowane przez {@link WarningLevel}:
 * {@link WarningLevel#YELLOW}  — 1. stopień (zjawisko może być niebezpieczne)
 * {@link WarningLevel#ORANGE}  — 2. stopień (zjawisko jest niebezpieczne)
 * {@link WarningLevel#RED}     — 3. stopień (zjawisko jest bardzo niebezpieczne)
 */
public class Warning {

    /** Unikalny identyfikator ostrzeżenia z systemu IMGW. */
    private String id;

    /**
     * Identyfikator stacji, której dotyczy ostrzeżenie.
     * {@code null} gdy ostrzeżenie jest regionalne (nie powiązane z konkretną stacją).
     */
    private String stationId;

    /** Poziom/stopień ostrzeżenia. */
    private WarningLevel level;

    /** Typ ostrzeżenia: {@link StationType#METEO} lub {@link StationType#HYDRO}. */
    private StationType type;

    /**
     * Nazwa zjawiska meteorologicznego/hydrologicznego
     * (np. {@code "Silny wiatr"}, {@code "Roztopy"}, {@code "Burza z gradem"}).
     */
    private String phenomenon;

    /**
     * Prawdopodobieństwo wystąpienia zjawiska [%].
     * {@code -1} gdy IMGW nie podało wartości.
     */
    private int probability;

    /**
     * Pełna treść komunikatu ostrzeżenia, taka jak ją wystawia IMGW.
     * Dla meteo to pole {@code tresc}, dla hydro — {@code przebieg}.
     * Wyświetlane w dialogu szczegółów (dwuklik w wiersz w {@code WarningPanel}).
     */
    private String message;

    /**
     * Biuro IMGW, które wystawiło ostrzeżenie (pole {@code biuro} w obu endpointach).
     * Może być {@code null} jeśli API nie podało wartości.
     */
    private String office;

    /** Data i czas wydania ostrzeżenia. */
    private LocalDateTime issuedAt;

    /**
     * Data i czas ważności ostrzeżenia (koniec obowiązywania).
     * {@code null} gdy ostrzeżenie obowiązuje bezterminowo.
     */
    private LocalDateTime validUntil;

    /** Konstruktor bezargumentowy wymagany przez Gson / JDBC. */
    public Warning() {
        this.probability = -1;
    }

    /**
     * Konstruktor z polami wymaganymi — używany w testach jednostkowych.
     *
     * @param id         identyfikator ostrzeżenia
     * @param level      poziom alertu
     * @param type       typ (METEO / HYDRO)
     * @param phenomenon nazwa zjawiska
     * @param issuedAt   data wydania
     * @param validUntil data wygaśnięcia, może być {@code null}
     */
    public Warning(String id, WarningLevel level, StationType type, String phenomenon,
                   LocalDateTime issuedAt, LocalDateTime validUntil) {
        this.id = id;
        this.level = level;
        this.type = type;
        this.phenomenon = phenomenon;
        this.issuedAt = issuedAt;
        this.validUntil = validUntil;
        this.probability = -1;
        this.message = phenomenon;
    }

    /**
     * Sprawdza czy ostrzeżenie jest nadal ważne (nie wygasło).
     *
     * @return {@code true} jeśli {@code validUntil} jest w przyszłości lub jest {@code null}
     */
    public boolean isActive() {
        if (validUntil == null) return true;
        return LocalDateTime.now().isBefore(validUntil);
    }

    /**
     * Sprawdza czy ostrzeżenie osiąga co najmniej podany poziom ważności.
     * Używane przez {@code WarningService} do filtrowania.
     *
     * @param minLevel minimalny poziom do spełnienia
     * @return {@code true} jeśli {@code this.level} jest co najmniej {@code minLevel}
     */
    public boolean meetsLevel(WarningLevel minLevel) {
        if (level == null || minLevel == null) return false;
        return level.isAtLeast(minLevel);
    }

    /**
     * Zwraca skrócony opis ostrzeżenia do wyświetlania w tabeli GUI.
     *
     * @return np. {@code "[RED] METEO: Silny wiatr (85%)"}
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        if (level != null)      sb.append("[").append(level.name()).append("] ");
        if (type != null)       sb.append(type.name()).append(": ");
        sb.append(phenomenon != null ? phenomenon : "Ostrzeżenie");
        if (probability > 0)    sb.append(" (").append(probability).append("%)");
        return sb.toString();
    }

    /**
     * Zwraca kolor tła dla wiersza w tabeli GUI na podstawie poziomu alertu.
     * Deleguje do {@link WarningLevel#getColor()}.
     *
     * @return kolor AWT lub {@code null} gdy brak poziomu
     */
    public java.awt.Color getDisplayColor() {
        return level != null ? level.getColor() : null;
    }

    /** Dwa ostrzeżenia są równe gdy mają ten sam {@code id}. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Warning other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
                "Warning{id='%s', level=%s, type=%s, phenomenon='%s', office='%s', active=%b, validUntil=%s}",
                id, level, type, phenomenon, office, isActive(), validUntil);
    }

    /** @return identyfikator ostrzeżenia w systemie IMGW */
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    /** @return identyfikator powiązanej stacji lub {@code null} dla ostrzeżeń regionalnych */
    public String getStationId() { return stationId; }
    public void setStationId(String stationId) { this.stationId = stationId; }

    /** @return poziom ostrzeżenia (YELLOW / ORANGE / RED) */
    public WarningLevel getLevel() { return level; }
    public void setLevel(WarningLevel level) { this.level = level; }

    /** @return typ ostrzeżenia (METEO / HYDRO) */
    public StationType getType() { return type; }
    public void setType(StationType type) { this.type = type; }

    /** @return nazwa zjawiska (np. {@code "Silny wiatr"}) */
    public String getPhenomenon() { return phenomenon; }
    public void setPhenomenon(String phenomenon) { this.phenomenon = phenomenon; }

    /** @return prawdopodobieństwo zjawiska [%] lub {@code -1} gdy nieznane */
    public int getProbability() { return probability; }
    public void setProbability(int probability) { this.probability = probability; }

    /**
     * @return pełna treść ostrzeżenia (z pola {@code tresc} dla meteo
     *         lub {@code przebieg} dla hydro)
     */
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    /**
     * @return biuro IMGW, które wydało ostrzeżenie (pole {@code biuro} w API);
     *         {@code null} gdy nie podano
     */
    public String getOffice() { return office; }
    public void setOffice(String office) { this.office = office; }

    /** @return data i czas wydania ostrzeżenia */
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime t) { this.issuedAt = t; }

    /** @return data wygaśnięcia ostrzeżenia lub {@code null} gdy bezterminowe */
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime t) { this.validUntil = t; }
}