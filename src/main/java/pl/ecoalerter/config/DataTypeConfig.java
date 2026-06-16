package pl.ecoalerter.config;

import pl.ecoalerter.model.WarningLevel;

/**
 * Konfiguracja zakresu monitorowanych danych.
 *
 * Każda opcja odpowiada kluczowi w {@code app.properties}.
 * Obiekt tworzony przez {@link AppConfig#getDataTypeConfig()}.
 *
 * # Grupy danych
 * data.meteo.enabled=true
 * data.hydro.enabled=true
 * data.warnings.enabled=true
 *
 * # Pola meteo
 * data.meteo.temperature=true
 * data.meteo.wind=true
 * data.meteo.precipitation=true
 * data.meteo.pressure=true
 *
 * # Pola hydro
 * data.hydro.waterLevel=true
 * data.hydro.waterTemperature=true
 *
 * # Filtrowanie ostrzeżeń
 * warnings.filter.level=YELLOW
*/
public class DataTypeConfig {
    private boolean meteoEnabled;
    private boolean hydroEnabled;
    private boolean warningsEnabled;

    private boolean temperatureEnabled;
    private boolean windEnabled;
    private boolean precipitationEnabled;
    private boolean pressureEnabled;

    private boolean waterLevelEnabled;
    private boolean waterTemperatureEnabled;

    private WarningLevel warningMinLevel;

    /** Tworzy konfigurację z domyślnymi wartościami (wszystko włączone, poziom YELLOW). */
    public DataTypeConfig() {
        this.meteoEnabled        = true;
        this.hydroEnabled        = true;
        this.warningsEnabled     = true;
        this.temperatureEnabled  = true;
        this.windEnabled         = true;
        this.precipitationEnabled = true;
        this.pressureEnabled     = true;
        this.waterLevelEnabled   = true;
        this.waterTemperatureEnabled = true;
        this.warningMinLevel     = WarningLevel.YELLOW;
    }

    /**
     * Czy w ogóle którakolwiek kategoria danych jest włączona.
     * Jeśli false — scheduler nie ma co zbierać.
    */
    public boolean hasAnyEnabled() {
        return meteoEnabled || hydroEnabled || warningsEnabled;
    }

    /** Czy jakiekolwiek pole meteo jest włączone (przy włączonej grupie meteo). */
    public boolean hasAnyMeteoField() {
        return temperatureEnabled || windEnabled || precipitationEnabled || pressureEnabled;
    }

    /** Czy jakiekolwiek pole hydro jest włączone (przy włączonej grupie hydro). */
    public boolean hasAnyHydroField() {
        return waterLevelEnabled || waterTemperatureEnabled;
    }

    /** Zwraca czytelny opis konfiguracji do logowania. */
    @Override
    public String toString() {
        return String.format(
                "DataTypeConfig{meteo=%b(temp=%b,wind=%b,precip=%b,press=%b), " +
                "hydro=%b(level=%b,temp=%b), warnings=%b(minLevel=%s)}",
                meteoEnabled, temperatureEnabled, windEnabled, precipitationEnabled, pressureEnabled,
                hydroEnabled, waterLevelEnabled, waterTemperatureEnabled,
                warningsEnabled, warningMinLevel
        );
    }

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

    public boolean isPressureEnabled()         { return pressureEnabled; }
    public void setPressureEnabled(boolean v)  { this.pressureEnabled = v; }

    public boolean isWaterLevelEnabled()       { return waterLevelEnabled; }
    public void setWaterLevelEnabled(boolean v) { this.waterLevelEnabled = v; }

    public boolean isWaterTemperatureEnabled() { return waterTemperatureEnabled; }
    public void setWaterTemperatureEnabled(boolean v) { this.waterTemperatureEnabled = v; }

    public WarningLevel getWarningMinLevel()   { return warningMinLevel; }
    public void setWarningMinLevel(WarningLevel v) { this.warningMinLevel = v; }
}