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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy integracyjne FileRepository (format JSON — jedyny obsługiwany format plikowy).
 * Katalog danych tworzony jest w tymczasowym folderze @TempDir.
 */
class FileRepositoryTest {

    @TempDir
    Path tempDir;

    private FileRepository repo;

    private static final LocalDateTime T1      = LocalDateTime.of(2024, 6, 14, 10, 0);
    private static final LocalDateTime T2      = LocalDateTime.of(2024, 6, 14, 11, 0);
    private static final LocalDateTime DAY_AGO = T1.minusDays(1);

    @BeforeEach
    void setUp() throws Exception {
        PathResolver resolver = new PathResolver(
                tempDir.resolve("data").toString(),
                tempDir.resolve("logs").toString()
        );
        resolver.createRequiredDirectories();
        repo = new FileRepository(resolver);
    }

    // =========================================================================
    // STACJE
    // =========================================================================

    @Test
    void saveStation_andFindAll_returnsSavedStation() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));

        List<Station> all = repo.findAllStations();

        assertEquals(1, all.size());
        assertEquals("12200", all.get(0).getId());
        assertEquals(StationType.METEO, all.get(0).getType());
    }

    @Test
    void saveStation_upsert_doesNotDuplicate() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveStation(meteoStation("12200", "WARSZAWA-OKECIE"));

        assertEquals(1, repo.findAllStations().size());
        assertEquals("WARSZAWA-OKECIE", repo.findAllStations().get(0).getName());
    }

    @Test
    void deleteStation_removesFromList() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveStation(meteoStation("12385", "KRAKOW"));

        repo.deleteStation("12200", StationType.METEO);

        List<Station> remaining = repo.findAllStations();
        assertEquals(1, remaining.size());
        assertEquals("12385", remaining.get(0).getId());
    }

    @Test
    void findActiveStations_filtersCorrectly() throws PersistenceException {
        Station active   = meteoStation("12200", "WARSZAWA");
        Station inactive = meteoStation("12385", "KRAKOW");
        inactive.setActive(false);

        repo.saveStation(active);
        repo.saveStation(inactive);
        repo.saveStation(hydroStation("150180180", "Wisła-Warszawa"));

        List<Station> activeMeteo = repo.findActiveStations(StationType.METEO);

        assertEquals(1, activeMeteo.size());
        assertEquals("12200", activeMeteo.get(0).getId());
    }

    @Test
    void findAllStations_whenNoFile_returnsEmptyList() throws PersistenceException {
        assertTrue(repo.findAllStations().isEmpty());
    }

    // =========================================================================
    // DANE METEOROLOGICZNE
    // =========================================================================

    @Test
    void saveMeteo_andFindByStation_requiresSavedStationForNameResolution()
            throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveMeteo(meteoData("12200", T1, 22.4, 3.1));

        List<MeteoData> result = repo.findMeteoByStation("12200");

        assertEquals(1, result.size());
        assertEquals(22.4, result.get(0).getTemperature(), 0.001);
    }

    @Test
    void saveAllMeteo_savesAllRecords() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveAllMeteo(List.of(
                meteoData("12200", T1, 22.4, 3.1),
                meteoData("12200", T2, 23.0, 2.8)
        ));

        assertEquals(2, repo.findMeteoByStation("12200").size());
    }

    @Test
    void findMeteoByStation_sortsDescending() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveMeteo(meteoData("12200", T1, 22.4, 3.1));
        repo.saveMeteo(meteoData("12200", T2, 23.0, 2.8));

        List<MeteoData> result = repo.findMeteoByStation("12200");

        assertEquals(T2, result.get(0).getTimestamp()); // nowszy pierwszy
    }

    @Test
    void findLatestMeteo_returnsNewestRecord() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveMeteo(meteoData("12200", T1, 22.4, 3.1));
        repo.saveMeteo(meteoData("12200", T2, 23.0, 2.8));

        Optional<MeteoData> latest = repo.findLatestMeteo("12200");

        assertTrue(latest.isPresent());
        assertEquals(T2, latest.get().getTimestamp());
    }

    @Test
    void findLatestMeteo_whenNoData_returnsEmpty() throws PersistenceException {
        assertTrue(repo.findLatestMeteo("99999").isEmpty());
    }

    @Test
    void findMeteoByStationAndRange_filtersCorrectly() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveMeteo(meteoData("12200", DAY_AGO, 15.0, 0.0));
        repo.saveMeteo(meteoData("12200", T1,      22.4, 3.1));
        repo.saveMeteo(meteoData("12200", T2,      23.0, 2.8));

        List<MeteoData> result = repo.findMeteoByStationAndRange("12200", T1, T2);

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(d -> d.getTimestamp().equals(DAY_AGO)));
    }

    @Test
    void deleteMeteoOlderThan_removesOldRecords() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.saveMeteo(meteoData("12200", DAY_AGO, 15.0, 0.0));
        repo.saveMeteo(meteoData("12200", T1,      22.4, 3.1));

        int deleted = repo.deleteMeteoOlderThan(T1);

        assertEquals(1, deleted);
        assertEquals(1, repo.findMeteoByStation("12200").size());
    }

    // =========================================================================
    // DANE HYDROLOGICZNE
    // =========================================================================

    @Test
    void saveHydro_andFindByStation_returnsRecord() throws PersistenceException {
        repo.saveStation(hydroStation("150180180", "Warszawa"));
        repo.saveHydro(hydroData("150180180", T1, 145.0, 14.5));

        List<HydroData> result = repo.findHydroByStation("150180180");

        assertEquals(1, result.size());
        assertEquals(145.0, result.get(0).getWaterLevel(), 0.001);
    }

    @Test
    void saveAllHydro_savesMultipleRecords() throws PersistenceException {
        repo.saveStation(hydroStation("150180180", "Warszawa"));
        repo.saveAllHydro(List.of(
                hydroData("150180180", T1, 145.0, 14.5),
                hydroData("150180180", T2, 147.0, 14.8)
        ));

        assertEquals(2, repo.findHydroByStation("150180180").size());
    }

    @Test
    void findLatestHydro_returnsNewestRecord() throws PersistenceException {
        repo.saveStation(hydroStation("150180180", "Warszawa"));
        repo.saveHydro(hydroData("150180180", T1, 145.0, 14.5));
        repo.saveHydro(hydroData("150180180", T2, 148.0, 15.0));

        Optional<HydroData> latest = repo.findLatestHydro("150180180");

        assertTrue(latest.isPresent());
        assertEquals(T2, latest.get().getTimestamp());
    }

    // =========================================================================
    // OSTRZEŻENIA
    // =========================================================================

    @Test
    void saveAllWarnings_andFindActive_returnsActiveOnly() throws PersistenceException {
        LocalDateTime now = LocalDateTime.now();
        List<Warning> warnings = List.of(
                new Warning("W001", WarningLevel.ORANGE, StationType.METEO, "Silny wiatr",
                        now, now.plusHours(6)),
                new Warning("W002", WarningLevel.YELLOW, StationType.HYDRO, "Wezbranie",
                        now, now.plusHours(3))
        );

        repo.saveAllWarnings(warnings);
        List<Warning> active = repo.findActiveWarnings();

        assertEquals(2, active.size());
    }

    @Test
    void findActiveWarningsByMinLevel_filtersCorrectly() throws PersistenceException {
        LocalDateTime now = LocalDateTime.now();
        repo.saveAllWarnings(List.of(
                new Warning("W001", WarningLevel.YELLOW, StationType.METEO, "test",
                        now, now.plusHours(1)),
                new Warning("W002", WarningLevel.RED,    StationType.METEO, "test",
                        now, now.plusHours(1))
        ));

        List<Warning> result = repo.findActiveWarningsByMinLevel(WarningLevel.ORANGE);

        assertEquals(1, result.size());
        assertEquals(WarningLevel.RED, result.get(0).getLevel());
    }

    @Test
    void close_doesNotThrow() {
        assertDoesNotThrow(() -> repo.close());
    }

    // =========================================================================
    // FABRYKI
    // =========================================================================

    private Station meteoStation(String id, String name) {
        return new Station(id, name, StationType.METEO, true, 300);
    }

    private Station hydroStation(String id, String name) {
        return new Station(id, name, StationType.HYDRO, true, 300);
    }

    private MeteoData meteoData(String id, LocalDateTime ts, double temp, double wind) {
        return new MeteoData(id, "TEST", ts, temp, wind, 0.0);
    }

    private HydroData hydroData(String id, LocalDateTime ts, double level, double temp) {
        return new HydroData(id, "TEST", "Wisła", ts, level, temp);
    }
}