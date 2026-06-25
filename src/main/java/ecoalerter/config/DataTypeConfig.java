package ecoalerter.config;

import ecoalerter.model.WarningLevel;

/**
 * Konfiguracja zakresu monitorowanych danych.
 *
 * <p>Każda opcja odpowiada kluczowi w {@code app.properties}.
 * Obiekt tworzony przez {@link AppConfig#getDataTypeConfig()}.
 *
 * <pre>
 * # Grupy danych
 * data.meteo.enabled=true
 * data.hydro.enabled=true
 * data.warnings.enabled=true
 *
 * # Pola meteo
 * data.meteo.temperature=true
 * data.meteo.wind=true
 * data.meteo.precipitation=true
 *
 * # Pola hydro
 * data.hydro.waterLevel=true
 * data.hydro.waterTemperature=true
 * data.hydro.phenomena=true
 *
 * # Filtrowanie ostrzeżeń
 * warnings.filter.level=YELLOW
 * </pre>
 */
public class DataTypeConfig {

    // -------------------------------------------------------------------------
    // Grupy główne
    // -------------------------------------------------------------------------

    private boolean meteoEnabled;
    private boolean hydroEnabled;
    private boolean warningsEnabled;

    // -------------------------------------------------------------------------
    // Pola meteo
    // -------------------------------------------------------------------------

    private boolean temperatureEnabled;
    private boolean windEnabled;
    private boolean precipitationEnabled;

    // -------------------------------------------------------------------------
    // Pola hydro
    // -------------------------------------------------------------------------

    private boolean waterLevelEnabled;
    private boolean waterTemperatureEnabled;

    /**
     * Pokazywanie kolumny zjawisk hydro (lód + zarastanie razem).
     * Jedna flaga dla obu — w GUI prezentowane są w jednej kolumnie „Zjawiska".
     */
    private boolean hydroPhenomenaEnabled;

    // -------------------------------------------------------------------------
    // Ostrzeżenia
    // -------------------------------------------------------------------------

    /** Minimalny poziom alertu wyświetlanego/zapisywanego. */
    private WarningLevel warningMinLevel;

    // -------------------------------------------------------------------------
    // Konstruktory
    // -------------------------------------------------------------------------

    /** Tworzy konfigurację z domyślnymi wartościami (wszystko włączone, poziom YELLOW). */
    public DataTypeConfig() {
        this.meteoEnabled            = true;
        this.hydroEnabled            = true;
        this.warningsEnabled         = true;
        this.temperatureEnabled      = true;
        this.windEnabled             = true;
        this.precipitationEnabled    = true;
        this.waterLevelEnabled       = true;
        this.waterTemperatureEnabled = true;
        this.hydroPhenomenaEnabled   = true;
        this.warningMinLevel         = WarningLevel.YELLOW;
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    /**
     * Czy w ogóle którakolwiek kategoria danych jest włączona.
     * Jeśli false — scheduler nie ma co zbierać.
     */
    public boolean hasAnyEnabled() {
        return meteoEnabled || hydroEnabled || warningsEnabled;
    }

    /**
     * Czy jakiekolwiek pole meteo jest włączone (przy włączonej grupie meteo).
     */
    public boolean hasAnyMeteoField() {
        return temperatureEnabled || windEnabled || precipitationEnabled;
    }

    /**
     * Czy jakiekolwiek pole hydro jest włączone (przy włączonej grupie hydro).
     * Uwzględnia też przełącznik zjawisk hydro — gdy WSZYSTKIE pola hydro
     * są wyłączone, DataViewPanel pokazuje tylko kolumny dat i nic poza tym.
     */
    public boolean hasAnyHydroField() {
        return waterLevelEnabled || waterTemperatureEnabled || hydroPhenomenaEnabled;
    }

    /**
     * Zwraca czytelny opis konfiguracji do logowania.
     */
    @Override
    public String toString() {
        return String.format(
                "DataTypeConfig{meteo=%b(temp=%b,wind=%b,precip=%b), " +
                "hydro=%b(level=%b,temp=%b,phenomena=%b), warnings=%b(minLevel=%s)}",
                meteoEnabled, temperatureEnabled, windEnabled, precipitationEnabled,
                hydroEnabled, waterLevelEnabled, waterTemperatureEnabled, hydroPhenomenaEnabled,
                warningsEnabled, warningMinLevel
        );
    }

    // -------------------------------------------------------------------------
    // Gettery i settery
    // -------------------------------------------------------------------------

    public boolean isMeteoEnabled()            { return meteoEnabled; }
    public void setMeteoEnabled(boolean v)     { this.meteoEnabled = v; }

    public boolean isHydroEnabled()            { return hydroEnabled; }
    public void setHydroEnabled(boolean v)     { this.hydroEnabled = v; }

    public boolean isWarningsEnabled()         { return warningsEnabled; }
    public void setWarningsEnabled(boolean v)  { this.warningsEnabled = v; }

    public boolean isTemperatureEnabled()      { return temperatureEnabled; }
    public void setTemperatureEnabled(boolean v) { this.temperatureEnabled = v; }

    public boolean isWindEnabled()             { return windEnabled; }
    public void setWindEnabled(boolean v)      { this.windEnabled = v; }

    public boolean isPrecipitationEnabled()    { return precipitationEnabled; }
    public void setPrecipitationEnabled(boolean v) { this.precipitationEnabled = v; }

    public boolean isWaterLevelEnabled()       { return waterLevelEnabled; }
    public void setWaterLevelEnabled(boolean v) { this.waterLevelEnabled = v; }

    public boolean isWaterTemperatureEnabled() { return waterTemperatureEnabled; }
    public void setWaterTemperatureEnabled(boolean v) { this.waterTemperatureEnabled = v; }

    /** @return {@code true} jeśli kolumna zjawisk hydro (lód + zarastanie) ma być widoczna w GUI */
    public boolean isHydroPhenomenaEnabled()   { return hydroPhenomenaEnabled; }
    public void setHydroPhenomenaEnabled(boolean v) { this.hydroPhenomenaEnabled = v; }

    public WarningLevel getWarningMinLevel()   { return warningMinLevel; }
    public void setWarningMinLevel(WarningLevel v) { this.warningMinLevel = v; }
}