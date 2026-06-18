package ecoalerter.persistence.file;

import ecoalerter.model.*;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.PathResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy jednostkowe JsonFileWriter.
 * Weryfikują poprawność zapisu i odczytu danych JSON oraz zachowanie przy dopisywaniu.
 */
class JsonFileWriterTest {

    @TempDir
    Path tempDir;

    private JsonFileWriter writer;

    private static final LocalDateTime T1 = LocalDateTime.of(2024, 6, 14, 12, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2024, 6, 14, 13, 0);

    @BeforeEach
    void setUp() throws Exception {
        PathResolver resolver = new PathResolver(
                tempDir.resolve("data").toString(),
                tempDir.resolve("logs").toString()
        );
        resolver.createRequiredDirectories();
        writer = new JsonFileWriter(resolver);
    }

    // =========================================================================
    // METEO
    // =========================================================================

    @Test
    void writeMeteo_createsFileAndCanBeRead() throws PersistenceException {
        MeteoData data = meteoData("12200", "WARSZAWA", T1, 22.4, 3.1);

        writer.writeMeteo(data);
        List<MeteoData> result = writer.readMeteo("12200", "WARSZAWA");

        assertEquals(1, result.size());
        assertEquals("12200", result.get(0).getStationId());
        assertEquals(22.4, result.get(0).getTemperature(), 0.001);
        assertEquals(3.1,  result.get(0).getWindSpeed(),   0.001);
    }

    @Test
    void writeMeteo_appendsToExistingFile() throws PersistenceException {
        writer.writeMeteo(meteoData("12200", "WARSZAWA", T1, 22.4, 3.1));
        writer.writeMeteo(meteoData("12200", "WARSZAWA", T2, 23.0, 2.8));

        List<MeteoData> result = writer.readMeteo("12200", "WARSZAWA");

        assertEquals(2, result.size());
    }

    @Test
    void writeMeteoList_writesAllRecordsToCorrectFiles() throws PersistenceException {
        List<MeteoData> batch = List.of(
                meteoData("12200", "WARSZAWA", T1, 22.4, 3.1),
                meteoData("12385", "KRAKOW",   T1, 20.0, 1.5)
        );

        writer.writeMeteoList(batch);

        assertEquals(1, writer.readMeteo("12200", "WARSZAWA").size());
        assertEquals(1, writer.readMeteo("12385", "KRAKOW").size());
    }

    @Test
    void readMeteo_whenFileDoesNotExist_returnsEmptyList() throws PersistenceException {
        List<MeteoData> result = writer.readMeteo("99999", "NIEISTNIEJE");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void writeMeteo_withNullFields_preservesNulls() throws PersistenceException {
        MeteoData data = new MeteoData("12200", "WARSZAWA", T1, null, null, null, null);

        writer.writeMeteo(data);
        MeteoData loaded = writer.readMeteo("12200", "WARSZAWA").get(0);

        assertNull(loaded.getTemperature());
        assertNull(loaded.getWindSpeed());
    }

    @Test
    void writeMeteoList_withEmptyList_doesNotCreateFile() throws PersistenceException {
        writer.writeMeteoList(List.of());

        List<MeteoData> result = writer.readMeteo("12200", "WARSZAWA");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // HYDRO
    // =========================================================================

    @Test
    void writeHydro_createsFileAndCanBeRead() throws PersistenceException {
        HydroData data = hydroData("150180180", "Warszawa", T1, 145.0, 14.5);

        writer.writeHydro(data);
        List<HydroData> result = writer.readHydro("150180180", "Warszawa");

        assertEquals(1, result.size());
        assertEquals("150180180", result.get(0).getStationId());
        assertEquals(145.0, result.get(0).getWaterLevel(),       0.001);
        assertEquals(14.5,  result.get(0).getWaterTemperature(), 0.001);
    }

    @Test
    void writeHydro_appendsToExistingFile() throws PersistenceException {
        writer.writeHydro(hydroData("150180180", "Warszawa", T1, 145.0, 14.5));
        writer.writeHydro(hydroData("150180180", "Warszawa", T2, 147.0, 14.8));

        assertEquals(2, writer.readHydro("150180180", "Warszawa").size());
    }

    @Test
    void readHydro_whenFileDoesNotExist_returnsEmptyList() throws PersistenceException {
        assertTrue(writer.readHydro("99999", "BRAK").isEmpty());
    }

    @Test
    void writeHydroList_withNullList_doesNotThrow() {
        assertDoesNotThrow(() -> writer.writeHydroList(null));
    }

    // =========================================================================
    // OSTRZEŻENIA
    // =========================================================================

    @Test
    void writeWarnings_createsFileWithAllWarnings() throws PersistenceException {
        List<Warning> warnings = List.of(
                warning("W001", WarningLevel.ORANGE, StationType.METEO),
                warning("W002", WarningLevel.RED,    StationType.HYDRO)
        );

        writer.writeWarnings(warnings);

        String today = java.time.LocalDate.now().toString();
        List<Warning> result = writer.readWarnings(today);

        assertEquals(2, result.size());
    }

    @Test
    void writeWarnings_overwritesPreviousContent() throws PersistenceException {
        writer.writeWarnings(List.of(warning("W001", WarningLevel.YELLOW, StationType.METEO)));
        writer.writeWarnings(List.of(warning("W002", WarningLevel.RED,    StationType.METEO)));

        String today = java.time.LocalDate.now().toString();
        List<Warning> result = writer.readWarnings(today);

        assertEquals(1, result.size());
        assertEquals("W002", result.get(0).getId());
    }

    @Test
    void writeWarnings_withEmptyList_writesEmptyArray() throws PersistenceException {
        writer.writeWarnings(List.of());

        String today = java.time.LocalDate.now().toString();
        List<Warning> result = writer.readWarnings(today);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void readWarnings_forNonexistentDate_returnsEmptyList() throws PersistenceException {
        assertTrue(writer.readWarnings("1900-01-01").isEmpty());
    }

    // =========================================================================
    // FABRYKI
    // =========================================================================

    private MeteoData meteoData(String id, String name, LocalDateTime ts,
                                double temp, double wind) {
        return new MeteoData(id, name, ts, temp, wind, 0.0, 1013.0);
    }

    private HydroData hydroData(String id, String name, LocalDateTime ts,
                                double level, double temp) {
        return new HydroData(id, name, "Wisła", ts, level, temp);
    }

    private Warning warning(String id, WarningLevel level, StationType type) {
        LocalDateTime now = LocalDateTime.now();
        return new Warning(id, level, type, "Zjawisko testowe",
                now, now.plusHours(6));
    }
}