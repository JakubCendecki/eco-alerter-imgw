package ecoalerter.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Model pojedynczego pomiaru hydrologicznego ze stacji IMGW-PIB.
 *
 * <p>Pola odpowiadają odpowiedzi endpointu {@code /api/data/hydro}:
 * <ul>
 *   <li>{@code id_stacji}           → {@link #stationId}</li>
 *   <li>{@code stacja}              → {@link #stationName}</li>
 *   <li>{@code rzeka}               → {@link #riverName}</li>
 *   <li>{@code województwo}         → {@link #voivodeship}</li>
 *   <li>{@code stan_wody}           → {@link #waterLevel}</li>
 *   <li>{@code temperatura_wody}    → {@link #waterTemperature}</li>
 *   <li>{@code zjawisko_lodowe}     → {@link #icePhenomenon}</li>
 *   <li>{@code zjawisko_zarastania} → {@link #overgrowthPhenomenon}</li>
 *   <li>{@code przeplyw}            → {@link #flow}</li>
 * </ul>
 *
 * <p>Pola liczbowe są typu {@link Double} / {@code int}, co pozwala
 * na reprezentację braku pomiaru jako {@code null}.
 */
public class HydroData {

    /** Identyfikator stacji (klucz obcy do {@link Station#getId()}). */
    private String stationId;

    /** Czytelna nazwa stacji hydrologicznej. */
    private String stationName;

    /** Nazwa rzeki monitorowanej przez stację. */
    private String riverName;

    /** Województwo, w którym znajduje się stacja. */
    private String voivodeship;

    /** Data i czas pomiaru. */
    private LocalDateTime timestamp;

    /**
     * Stan wody [cm] — poziom wody nad zerem wodowskazowym.
     * {@code null} gdy brak pomiaru.
     */
    private Double waterLevel;

    /**
     * Temperatura wody [°C].
     * {@code null} gdy stacja nie dostarczyła tej wartości.
     */
    private Double waterTemperature;

    /**
     * Zjawisko lodowe (kod IMGW).
     * {@code 0} — brak zjawisk lodowych.
     * Wartości > 0 oznaczają różne stadia zlodzenia.
     */
    private int icePhenomenon;

    /**
     * Zjawisko zarastania (kod IMGW).
     * {@code 0} — brak zjawisk zarastania.
     */
    private int overgrowthPhenomenon;

    /**
     * Przepływ wody [m³/s].
     * {@code null} gdy stacja nie mierzy przepływu.
     */
    private Double flow;

    // -------------------------------------------------------------------------
    // Konstruktory
    // -------------------------------------------------------------------------

    /** Konstruktor bezargumentowy wymagany przez Gson / JDBC. */
    public HydroData() {}

    /**
     * Konstruktor z kluczowymi polami — używany w testach jednostkowych.
     *
     * @param stationId    identyfikator stacji IMGW
     * @param stationName  nazwa stacji
     * @param riverName    nazwa rzeki
     * @param timestamp    data i czas pomiaru
     * @param waterLevel   stan wody [cm], może być {@code null}
     * @param waterTemperature temperatura wody [°C], może być {@code null}
     */
    public HydroData(String stationId, String stationName, String riverName,
                     LocalDateTime timestamp, Double waterLevel, Double waterTemperature) {
        this.stationId        = stationId;
        this.stationName      = stationName;
        this.riverName        = riverName;
        this.timestamp        = timestamp;
        this.waterLevel       = waterLevel;
        this.waterTemperature = waterTemperature;
        this.icePhenomenon    = 0;
        this.overgrowthPhenomenon = 0;
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    /**
     * Sprawdza czy aktualnie występuje zjawisko lodowe.
     *
     * @return {@code true} jeśli kod zjawiska lodowego jest większy od zera
     */
    public boolean hasIcePhenomenon() {
        return icePhenomenon > 0;
    }

    /**
     * Sprawdza czy aktualnie występuje zjawisko zarastania.
     *
     * @return {@code true} jeśli kod zjawiska zarastania jest większy od zera
     */
    public boolean hasOvergrowthPhenomenon() {
        return overgrowthPhenomenon > 0;
    }

    /**
     * Sprawdza czy obiekt zawiera co najmniej jedną wartość pomiarową (nie null).
     *
     * @return {@code true} jeśli przynajmniej stan wody lub temperatura są ustawione
     */
    public boolean hasAnyMeasurement() {
        return waterLevel != null || waterTemperature != null || flow != null;
    }

    /**
     * Zwraca opis pomiaru do wyświetlania w tabeli GUI.
     *
     * @return np. {@code "Wisła/Warszawa | 145 cm | 14.2°C"}
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        if (riverName != null && !riverName.isBlank()) {
            sb.append(riverName).append("/");
        }
        sb.append(stationName != null ? stationName : stationId);
        if (waterLevel != null) sb.append(" | ").append(String.format("%.0f cm", waterLevel));
        if (waterTemperature != null) sb.append(" | ").append(String.format("%.1f°C", waterTemperature));
        if (flow != null) sb.append(" | ").append(String.format("%.2f m³/s", flow));
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
        if (!(o instanceof HydroData other)) return false;
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
                "HydroData{stationId='%s', river='%s', time=%s, level=%s cm, temp=%s°C, flow=%s m³/s}",
                stationId, riverName, timestamp, waterLevel, waterTemperature, flow);
    }

    // -------------------------------------------------------------------------
    // Gettery i settery
    // -------------------------------------------------------------------------

    /** @return identyfikator stacji IMGW */
    public String getStationId()                         { return stationId; }
    public void setStationId(String stationId)           { this.stationId = stationId; }

    /** @return nazwa stacji hydrologicznej */
    public String getStationName()                       { return stationName; }
    public void setStationName(String stationName)       { this.stationName = stationName; }

    /** @return nazwa rzeki */
    public String getRiverName()                         { return riverName; }
    public void setRiverName(String riverName)           { this.riverName = riverName; }

    /** @return nazwa województwa */
    public String getVoivodeship()                       { return voivodeship; }
    public void setVoivodeship(String voivodeship)       { this.voivodeship = voivodeship; }

    /** @return data i czas pomiaru */
    public LocalDateTime getTimestamp()                  { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp)    { this.timestamp = timestamp; }

    /** @return stan wody [cm] lub {@code null} */
    public Double getWaterLevel()                        { return waterLevel; }
    public void setWaterLevel(Double waterLevel)         { this.waterLevel = waterLevel; }

    /** @return temperatura wody [°C] lub {@code null} */
    public Double getWaterTemperature()                  { return waterTemperature; }
    public void setWaterTemperature(Double t)            { this.waterTemperature = t; }

    /** @return kod zjawiska lodowego (0 = brak) */
    public int getIcePhenomenon()                        { return icePhenomenon; }
    public void setIcePhenomenon(int icePhenomenon)      { this.icePhenomenon = icePhenomenon; }

    /** @return kod zjawiska zarastania (0 = brak) */
    public int getOvergrowthPhenomenon()                 { return overgrowthPhenomenon; }
    public void setOvergrowthPhenomenon(int v)           { this.overgrowthPhenomenon = v; }

    /** @return przepływ [m³/s] lub {@code null} gdy brak pomiaru */
    public Double getFlow()                              { return flow; }
    public void setFlow(Double flow)                     { this.flow = flow; }
}