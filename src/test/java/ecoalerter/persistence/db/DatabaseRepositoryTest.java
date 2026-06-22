package ecoalerter.persistence.db;

import ecoalerter.config.AppConfig;
import ecoalerter.model.*;
import ecoalerter.persistence.PersistenceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Testy integracyjne DatabaseRepository z prawdziwą bazą SQLite (plik tymczasowy).
 * Każdy test dostaje świeżą bazę danych dzięki @BeforeEach.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseRepositoryTest {

    @TempDir
    Path tempDir;

    @Mock
    AppConfig mockConfig;

    private ConnectionPool       pool;
    private DatabaseRepository   repo;

    private static final LocalDateTime NOW        = LocalDateTime.of(2024, 6, 14, 12, 0);
    private static final LocalDateTime HOUR_LATER = NOW.plusHours(1);
    private static final LocalDateTime DAY_AGO    = NOW.minusDays(1);

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = tempDir.resolve("test.db").toString();
        when(mockConfig.getDbUrl()).thenReturn("jdbc:sqlite:" + dbPath);
        when(mockConfig.getDbUser()).thenReturn("");
        when(mockConfig.getDbPassword()).thenReturn("");
        when(mockConfig.getDbPoolMax()).thenReturn(1);
        when(mockConfig.getRaw("db.connection.timeout.ms")).thenReturn("");

        pool = new ConnectionPool(mockConfig);
        new SchemaInitializer(pool).initialize();
        repo = new DatabaseRepository(pool);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
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
        assertEquals("WARSZAWA", all.get(0).getName());
        assertEquals(StationType.METEO, all.get(0).getType());
    }

    @Test
    void saveStation_upsert_updatesExistingStation() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));

        Station updated = meteoStation("12200", "WARSZAWA-OKECIE");
        updated.setActive(false);
        repo.saveStation(updated);

        List<Station> all = repo.findAllStations();
        assertEquals(1, all.size());
        assertEquals("WARSZAWA-OKECIE", all.get(0).getName());
        assertFalse(all.get(0).isActive());
    }

    @Test
    void findActiveStations_returnsOnlyActiveMeteoStations() throws PersistenceException {
        Station active   = meteoStation("12200", "WARSZAWA");
        Station inactive = meteoStation("12201", "KRAKÓW");
        inactive.setActive(false);

        repo.saveStation(active);
        repo.saveStation(inactive);
        repo.saveStation(hydroStation("150180180", "Wisła-Warszawa"));

        List<Station> activeMeteo = repo.findActiveStations(StationType.METEO);

        assertEquals(1, activeMeteo.size());
        assertEquals("12200", activeMeteo.get(0).getId());
    }

    @Test
    void deleteStation_removesFromDatabase() throws PersistenceException {
        repo.saveStation(meteoStation("12200", "WARSZAWA"));
        repo.deleteStation("12200", StationType.METEO);

        assertTrue(repo.findAllStations().isEmpty());
    }

    @Test
    void findAllStations_whenEmpty_returnsEmptyList() throws PersistenceException {
        assertTrue(repo.findAllStations().isEmpty());
    }

    // =========================================================================
    // DANE METEOROLOGICZNE
    // =========================================================================

    @Test
    void saveMeteo_andFindByStation_returnsRecord() throws PersistenceException {
        repo.saveMeteo(meteoData("12200", NOW, 22.4, 3.1, 0.0));

        List<MeteoData> result = repo.findMeteoByStation("12200");

        assertEquals(1, result.size());
        MeteoData d = result.get(0);
        assertEquals("12200", d.getStationId());
        assertEquals(22.4, d.getTemperature(), 0.001);
        assertEquals(3.1,  d.getWindSpeed(),   0.001);
        assertEquals(0.0,  d.getPrecipitation(), 0.001);
    }

    @Test
    void saveMeteo_duplicate_isIgnored() throws PersistenceException {
        MeteoData data = meteoData("12200", NOW, 22.4, 3.1, 0.0);
        repo.saveMeteo(data);
        repo.saveMeteo(data); // drugi zapis tego samego pomiaru

        assertEquals(1, repo.findMeteoByStation("12200").size());
    }

    @Test
    void saveAllMeteo_savesMultipleRecords() throws PersistenceException {
        List<MeteoData> batch = List.of(
                meteoData("12200", NOW,        22.4, 3.1, 0.0),
                meteoData("12200", HOUR_LATER, 23.1, 2.8, 0.0)
        );
        repo.saveAllMeteo(batch);

        assertEquals(2, repo.findMeteoByStation("12200").size());
    }

    @Test
    void findMeteoByStation_sortsDescendingByTimestamp() throws PersistenceException {
        repo.saveMeteo(meteoData("12200", NOW,        20.0, 0.0, 0.0));
        repo.saveMeteo(meteoData("12200", HOUR_LATER, 21.0, 0.0, 0.0));

        List<MeteoData> result = repo.findMeteoByStation("12200");

        assertEquals(HOUR_LATER, result.get(0).getTimestamp()); // nowszy jako pierwszy
        assertEquals(NOW,        result.get(1).getTimestamp());
    }

    @Test
    void findMeteoByStationAndRange_returnsOnlyRecordsInRange() throws PersistenceException {
        repo.saveMeteo(meteoData("12200", DAY_AGO,    15.0, 0.0, 0.0));
        repo.saveMeteo(meteoData("12200", NOW,        22.0, 0.0, 0.0));
        repo.saveMeteo(meteoData("12200", HOUR_LATER, 22.5, 0.0, 0.0));

        List<MeteoData> result = repo.findMeteoByStationAndRange("12200", NOW, HOUR_LATER);

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(d -> d.getTimestamp().equals(DAY_AGO)));
    }

    @Test
    void findLatestMeteo_returnsNewestRecord() throws PersistenceException {
        repo.saveMeteo(meteoData("12200", DAY_AGO,    15.0, 0.0, 0.0));
        repo.saveMeteo(meteoData("12200", NOW,        22.0, 0.0, 0.0));
        repo.saveMeteo(meteoData("12200", HOUR_LATER, 22.5, 0.0, 0.0));

        Optional<MeteoData> latest = repo.findLatestMeteo("12200");

        assertTrue(latest.isPresent());
        assertEquals(HOUR_LATER, latest.get().getTimestamp());
    }

    @Test
    void findLatestMeteo_whenNoData_returnsEmpty() throws PersistenceException {
        assertTrue(repo.findLatestMeteo("99999").isEmpty());
    }

    @Test
    void deleteMeteoOlderThan_removesOldRecords() throws PersistenceException {
        repo.saveMeteo(meteoData("12200", DAY_AGO, 15.0, 0.0, 0.0));
        repo.saveMeteo(meteoData("12200", NOW,     22.0, 0.0, 0.0));

        int deleted = repo.deleteMeteoOlderThan(NOW);

        assertEquals(1, deleted);
        List<MeteoData> remaining = repo.findMeteoByStation("12200");
        assertEquals(1, remaining.size());
        assertEquals(NOW, remaining.get(0).getTimestamp());
    }

    @Test
    void saveMeteo_withNullFields_savesNullsCorrectly() throws PersistenceException {
        MeteoData data = new MeteoData("12200", "WARSZAWA", NOW, null, null, null);
        repo.saveMeteo(data);

        MeteoData loaded = repo.findLatestMeteo("12200").orElseThrow();
        assertNull(loaded.getTemperature());
        assertNull(loaded.getWindSpeed());
        assertNull(loaded.getPrecipitation());
    }

    // =========================================================================
    // DANE HYDROLOGICZNE
    // =========================================================================

    @Test
    void saveHydro_andFindByStation_returnsRecord() throws PersistenceException {
        repo.saveHydro(hydroData("150180180", NOW, 145.0, 14.5, 250.0));

        List<HydroData> result = repo.findHydroByStation("150180180");

        assertEquals(1, result.size());
        HydroData d = result.get(0);
        assertEquals("150180180", d.getStationId());
        assertEquals(145.0, d.getWaterLevel(),       0.001);
        assertEquals(14.5,  d.getWaterTemperature(), 0.001);
        assertEquals(250.0, d.getFlow(),             0.001);
    }

    @Test
    void saveHydro_duplicate_isIgnored() throws PersistenceException {
        HydroData data = hydroData("150180180", NOW, 145.0, 14.5, null);
        repo.saveHydro(data);
        repo.saveHydro(data);

        assertEquals(1, repo.findHydroByStation("150180180").size());
    }

    @Test
    void saveAllHydro_savesMultipleRecords() throws PersistenceException {
        List<HydroData> batch = List.of(
                hydroData("150180180", NOW,        145.0, 14.5, null),
                hydroData("150180180", HOUR_LATER, 147.0, 14.6, null)
        );
        repo.saveAllHydro(batch);

        assertEquals(2, repo.findHydroByStation("150180180").size());
    }

    @Test
    void findLatestHydro_returnsNewestRecord() throws PersistenceException {
        repo.saveHydro(hydroData("150180180", DAY_AGO,    140.0, 12.0, null));
        repo.saveHydro(hydroData("150180180", NOW,        145.0, 14.5, null));
        repo.saveHydro(hydroData("150180180", HOUR_LATER, 148.0, 15.0, null));

        Optional<HydroData> latest = repo.findLatestHydro("150180180");

        assertTrue(latest.isPresent());
        assertEquals(HOUR_LATER, latest.get().getTimestamp());
        assertEquals(148.0, latest.get().getWaterLevel(), 0.001);
    }

    @Test
    void findHydroByStationAndRange_filtersCorrectly() throws PersistenceException {
        repo.saveHydro(hydroData("150180180", DAY_AGO,    140.0, 12.0, null));
        repo.saveHydro(hydroData("150180180", NOW,        145.0, 14.5, null));
        repo.saveHydro(hydroData("150180180", HOUR_LATER, 148.0, 15.0, null));

        List<HydroData> result = repo.findHydroByStationAndRange(
                "150180180", NOW, HOUR_LATER);

        assertEquals(2, result.size());
        assertEquals(NOW, result.get(0).getTimestamp()); // sortowanie rosnące
    }

    @Test
    void deleteHydroOlderThan_removesOldRecords() throws PersistenceException {
        repo.saveHydro(hydroData("150180180", DAY_AGO, 140.0, 12.0, null));
        repo.saveHydro(hydroData("150180180", NOW,     145.0, 14.5, null));

        int deleted = repo.deleteHydroOlderThan(NOW);

        assertEquals(1, deleted);
        assertEquals(1, repo.findHydroByStation("150180180").size());
    }

    // =========================================================================
    // OSTRZEŻENIA
    // =========================================================================

    @Test
    void saveWarning_andFindActive_returnsWarning() throws PersistenceException {
        repo.saveWarning(activeWarning("W001", WarningLevel.ORANGE, StationType.METEO));

        List<Warning> active = repo.findActiveWarnings();

        assertEquals(1, active.size());
        assertEquals("W001", active.get(0).getId());
        assertEquals(WarningLevel.ORANGE, active.get(0).getLevel());
    }

    @Test
    void saveWarning_upsert_updatesExisting() throws PersistenceException {
        Warning original = activeWarning("W001", WarningLevel.YELLOW, StationType.METEO);
        repo.saveWarning(original);

        Warning updated = activeWarning("W001", WarningLevel.RED, StationType.METEO);
        updated.setMessage("Zaktualizowana wiadomość");
        repo.saveWarning(updated);

        List<Warning> active = repo.findActiveWarnings();
        assertEquals(1, active.size());
        assertEquals(WarningLevel.RED, active.get(0).getLevel());
        assertEquals("Zaktualizowana wiadomość", active.get(0).getMessage());
    }

    @Test
    void findActiveWarnings_excludesExpiredWarnings() throws PersistenceException {
        Warning active  = activeWarning("W001", WarningLevel.ORANGE, StationType.METEO);
        Warning expired = expiredWarning("W002", WarningLevel.YELLOW, StationType.HYDRO);
        repo.saveWarning(active);
        repo.saveWarning(expired);

        List<Warning> result = repo.findActiveWarnings();

        assertEquals(1, result.size());
        assertEquals("W001", result.get(0).getId());
    }

    @Test
    void findActiveWarningsByMinLevel_filtersCorrectly() throws PersistenceException {
        repo.saveWarning(activeWarning("W001", WarningLevel.YELLOW, StationType.METEO));
        repo.saveWarning(activeWarning("W002", WarningLevel.ORANGE, StationType.METEO));
        repo.saveWarning(activeWarning("W003", WarningLevel.RED,    StationType.METEO));

        List<Warning> orangeAndAbove = repo.findActiveWarningsByMinLevel(WarningLevel.ORANGE);

        assertEquals(2, orangeAndAbove.size());
        assertTrue(orangeAndAbove.stream().noneMatch(w -> w.getLevel() == WarningLevel.YELLOW));
    }

    @Test
    void saveAllWarnings_savesAllInBatch() throws PersistenceException {
        List<Warning> warnings = List.of(
                activeWarning("W001", WarningLevel.YELLOW, StationType.METEO),
                activeWarning("W002", WarningLevel.RED,    StationType.HYDRO)
        );
        repo.saveAllWarnings(warnings);

        assertEquals(2, repo.findActiveWarnings().size());
    }

    @Test
    void deleteExpiredWarnings_removesOnlyExpired() throws PersistenceException {
        repo.saveWarning(activeWarning("W001",  WarningLevel.ORANGE, StationType.METEO));
        repo.saveWarning(expiredWarning("W002", WarningLevel.YELLOW, StationType.HYDRO));

        int deleted = repo.deleteExpiredWarnings();

        assertEquals(1, deleted);
        assertEquals(1, repo.findActiveWarnings().size());
        assertEquals("W001", repo.findActiveWarnings().get(0).getId());
    }

    @Test
    void findActiveWarnings_sortedByLevelDescending() throws PersistenceException {
        repo.saveWarning(activeWarning("W001", WarningLevel.YELLOW, StationType.METEO));
        repo.saveWarning(activeWarning("W002", WarningLevel.RED,    StationType.METEO));
        repo.saveWarning(activeWarning("W003", WarningLevel.ORANGE, StationType.METEO));

        List<Warning> result = repo.findActiveWarnings();

        assertEquals(WarningLevel.RED,    result.get(0).getLevel());
        assertEquals(WarningLevel.ORANGE, result.get(1).getLevel());
        assertEquals(WarningLevel.YELLOW, result.get(2).getLevel());
    }

    // =========================================================================
    // FABRYKI OBIEKTÓW TESTOWYCH
    // =========================================================================

    private Station meteoStation(String id, String name) {
        return new Station(id, name, StationType.METEO, true, 300);
    }

    private Station hydroStation(String id, String name) {
        return new Station(id, name, StationType.HYDRO, true, 300);
    }

    private MeteoData meteoData(String stationId, LocalDateTime ts,
                                double temp, double wind, double precip) {
        return new MeteoData(stationId, "TEST", ts, temp, wind, precip);
    }

    private HydroData hydroData(String stationId, LocalDateTime ts,
                                Double level, Double temp, Double flow) {
        HydroData d = new HydroData(stationId, "TEST", "Wisła", ts, level, temp);
        d.setFlow(flow);
        return d;
    }

    /**
     * Ostrzeżenie aktywne — issued_at = teraz, valid_until = za 6 godzin.
     * Obcięte do sekund — DB format yyyy-MM-dd HH:mm:ss traci nanosekund,
     * więc porównanie po round-tripie przez bazę dałoby AssertionError.
     */
    private Warning activeWarning(String id, WarningLevel level, StationType type) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new Warning(id, level, type, "Zjawisko testowe",
                now.minusMinutes(10), now.plusHours(6));
    }

    /**
     * Ostrzeżenie wygasłe — valid_until = godzina temu.
     * Obcięte do sekund z tego samego powodu co activeWarning.
     */
    private Warning expiredWarning(String id, WarningLevel level, StationType type) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new Warning(id, level, type, "Zjawisko przeszłe",
                now.minusHours(3), now.minusHours(1));
    }
}