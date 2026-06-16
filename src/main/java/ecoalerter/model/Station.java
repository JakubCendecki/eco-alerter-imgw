package ecoalerter.model;

import java.util.Objects;

/**
 * Model stacji pomiarowej IMGW-PIB.
 *
 * Reprezentuje zarówno stacje meteorologiczne (synop) jak i hydrologiczne.
 * Obiekt jest przechowywany w bazie danych / pliku {@code stations.json}
 * i ładowany przy starcie aplikacji.
 *
 * Pola {@code active} i {@code intervalSeconds} są zarządzane przez użytkownika
 * z poziomu GUI ({@code StationManagerPanel}).
 */
public class Station {

    /** Identyfikator stacji w systemie IMGW (np. {@code "12200"}, {@code "150180180"}). */
    private String id;

    /** Czytelna nazwa stacji (np. {@code "WARSZAWA"}, {@code "Wisła-Warszawa"}). */
    private String name;

    /** Typ stacji: {@link StationType#METEO} lub {@link StationType#HYDRO}. */
    private StationType type;

    /**
     * Czy stacja jest aktywna — czy scheduler ma pobierać dla niej dane.
     * Domyślnie {@code true} po dodaniu.
    */
    private boolean active;

    /**
     * Interwał odpytywania API dla tej stacji [sekundy].
     * Wartość {@code 0} oznacza użycie domyślnego interwału z konfiguracji.
    */
    private int intervalSeconds;

    /** Konstruktor bezargumentowy wymagany przez Gson / JDBC. */
    public Station() {
        this.active          = true;
        this.intervalSeconds = 0;
    }

    /**
     * Konstruktor z podstawowymi polami. Stacja domyślnie aktywna,
     * interwał według konfiguracji globalnej.
     *
     * @param id   identyfikator IMGW
     * @param name nazwa stacji
     * @param type typ stacji
    */
    public Station(String id, String name, StationType type) {
        this();
        this.id   = id;
        this.name = name;
        this.type = type;
    }

    /**
     * Konstruktor pełny.
     *
     * @param id              identyfikator IMGW
     * @param name            nazwa stacji
     * @param type            typ stacji
     * @param active          czy aktywna
     * @param intervalSeconds interwał odpytywania [s], 0 = domyślny
    */
    public Station(String id, String name, StationType type, boolean active, int intervalSeconds) {
        this.id              = id;
        this.name            = name;
        this.type            = type;
        this.active          = active;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * Zwraca efektywny interwał odpytywania dla tej stacji.
     * Jeśli {@code intervalSeconds == 0}, używa wartości domyślnej.
     *
     * @param defaultInterval domyślny interwał z {@code AppConfig}
     * @return interwał w sekundach, minimum 60
    */
    public int getEffectiveInterval(int defaultInterval) {
        int effective = intervalSeconds > 0 ? intervalSeconds : defaultInterval;
        return Math.max(effective, 60);
    }

    /**
     * Zwraca czytelny opis stacji do wyświetlania w GUI
     * (format: {@code NAZWA [TYP]}).
     *
     * @return np. {@code "WARSZAWA [METEO]"}
    */
    public String getDisplayLabel() {
        String typeName = type != null ? type.name() : "?";
        return (name != null ? name : id) + " [" + typeName + "]";
    }

    /** Dwie stacje są równe gdy mają ten sam {@code id} i {@code type}. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Station other)) return false;
        return Objects.equals(id, other.id) && type == other.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }

    @Override
    public String toString() {
        return String.format("Station{id='%s', name='%s', type=%s, active=%b, interval=%ds}",
                id, name, type, active, intervalSeconds);
    }

    /** @return identyfikator stacji w systemie IMGW */
    public String getId()               { return id; }
    public void setId(String id)        { this.id = id; }

    /** @return czytelna nazwa stacji */
    public String getName()             { return name; }
    public void setName(String name)    { this.name = name; }

    /** @return typ stacji (METEO / HYDRO) */
    public StationType getType()              { return type; }
    public void setType(StationType type)     { this.type = type; }

    /** @return {@code true} jeśli scheduler aktywnie zbiera dane dla tej stacji */
    public boolean isActive()                 { return active; }
    public void setActive(boolean active)     { this.active = active; }

    /** @return interwał odpytywania API [s]; 0 oznacza użycie domyślnego */
    public int getIntervalSeconds()                    { return intervalSeconds; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
}