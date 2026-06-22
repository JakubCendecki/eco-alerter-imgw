package ecoalerter.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Model pojedynczego pomiaru meteorologicznego ze stacji IMGW-PIB (sieć AWS, /api/data/meteo).
 *
 * Pola liczbowe są typu Double (obiektowego, nie prymitywnego), co pozwala
 * na reprezentację braku pomiaru jako null — API IMGW nie zawsze dostarcza
 * wszystkich wartości.
 *
 * Pola odpowiadają odpowiedzi endpointu /api/data/meteo:
 * - kod_stacji              -> stationId
 * - nazwa_stacji             -> stationName
 * - temperatura_powietrza   -> temperature
 * - wiatr_srednia_predkosc  -> windSpeed
 * - opad_10min              -> precipitation
 *
 * Sieć AWS nie mierzy ciśnienia atmosferycznego — model nie zawiera
 * takiego pola.
 */
public class MeteoData {

    /** Identyfikator stacji (klucz obcy do Station.getId()). */
    private String stationId;

    /** Czytelna nazwa stacji w momencie pomiaru. */
    private String stationName;

    /** Data i czas pomiaru. */
    private LocalDateTime timestamp;

    /**
     * Temperatura powietrza [°C].
     * null gdy stacja nie dostarczyła tej wartości.
     */
    private Double temperature;

    /**
     * Prędkość wiatru [m/s].
     * null gdy stacja nie dostarczyła tej wartości.
     */
    private Double windSpeed;

    /**
     * Suma opadów [mm].
     * null gdy stacja nie dostarczyła tej wartości.
     */
    private Double precipitation;

    // -------------------------------------------------------------------------
    // Konstruktory
    // -------------------------------------------------------------------------

    /** Konstruktor bezargumentowy wymagany przez Gson / JDBC. */
    public MeteoData() {}

    /**
     * Konstruktor pełny — używany w testach jednostkowych i przez MeteoApiService.
     *
     * @param stationId     identyfikator stacji IMGW
     * @param stationName   nazwa stacji
     * @param timestamp     data i czas pomiaru
     * @param temperature   temperatura [°C], może być null
     * @param windSpeed     prędkość wiatru [m/s], może być null
     * @param precipitation suma opadów [mm], może być null
     */
    public MeteoData(String stationId, String stationName, LocalDateTime timestamp,
                     Double temperature, Double windSpeed, Double precipitation) {
        this.stationId     = stationId;
        this.stationName   = stationName;
        this.timestamp     = timestamp;
        this.temperature   = temperature;
        this.windSpeed     = windSpeed;
        this.precipitation = precipitation;
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    /**
     * Sprawdza czy obiekt zawiera co najmniej jedną wartość pomiarową (nie null).
     * Używane do filtrowania pustych rekordów przed zapisem.
     *
     * @return true jeśli przynajmniej jedno pole pomiarowe jest ustawione
     */
    public boolean hasAnyMeasurement() {
        return temperature != null || windSpeed != null || precipitation != null;
    }

    /**
     * Zwraca opis pomiaru do wyświetlania w GUI — tylko dostępne pola.
     *
     * @return np. "WARSZAWA | 22.4°C | 3.1 m/s | 0.0 mm"
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder(stationName != null ? stationName : stationId);
        if (temperature   != null) sb.append(" | ").append(String.format("%.1f°C", temperature));
        if (windSpeed     != null) sb.append(" | ").append(String.format("%.1f m/s", windSpeed));
        if (precipitation != null) sb.append(" | ").append(String.format("%.1f mm", precipitation));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // equals, hashCode, toString
    // -------------------------------------------------------------------------

    /**
     * Dwa pomiary są równe gdy dotyczą tej samej stacji i tego samego czasu.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeteoData other)) return false;
        return Objects.equals(stationId, other.stationId)
            && Objects.equals(timestamp,  other.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stationId, timestamp);
    }

    @Override
    public String toString() {
        return String.format(
                "MeteoData{stationId='%s', name='%s', time=%s, temp=%s, wind=%s, precip=%s}",
                stationId, stationName, timestamp, temperature, windSpeed, precipitation);
    }

    // -------------------------------------------------------------------------
    // Gettery i settery
    // -------------------------------------------------------------------------

    /** @return identyfikator stacji IMGW */
    public String getStationId()                      { return stationId; }
    public void setStationId(String stationId)        { this.stationId = stationId; }

    /** @return nazwa stacji w momencie pomiaru */
    public String getStationName()                    { return stationName; }
    public void setStationName(String stationName)    { this.stationName = stationName; }

    /** @return data i czas pomiaru */
    public LocalDateTime getTimestamp()               { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    /** @return temperatura powietrza [°C] lub null */
    public Double getTemperature()                    { return temperature; }
    public void setTemperature(Double temperature)    { this.temperature = temperature; }

    /** @return prędkość wiatru [m/s] lub null */
    public Double getWindSpeed()                      { return windSpeed; }
    public void setWindSpeed(Double windSpeed)        { this.windSpeed = windSpeed; }

    /** @return suma opadów [mm] lub null */
    public Double getPrecipitation()                  { return precipitation; }
    public void setPrecipitation(Double precipitation){ this.precipitation = precipitation; }
}