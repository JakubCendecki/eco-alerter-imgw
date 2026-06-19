package ecoalerter.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testy jednostkowe ScheduleConfig.
 */
class ScheduleConfigTest {

    @TempDir
    Path tempDir;

    private ScheduleConfig config;

    private static final int DEFAULT_INTERVAL = 300;

    @BeforeEach
    void setUp() {
        config = new ScheduleConfig();
    }

    // -------------------------------------------------------------------------
    // getInterval
    // -------------------------------------------------------------------------

    @Test
    void getInterval_noCustom_returnsDefault() {
        int result = config.getInterval("12200", DEFAULT_INTERVAL);
        assertEquals(DEFAULT_INTERVAL, result);
    }

    @Test
    void getInterval_withCustom_returnsCustom() {
        config.setInterval("12200", 120);
        assertEquals(120, config.getInterval("12200", DEFAULT_INTERVAL));
    }

    @Test
    void getInterval_customForDifferentStation_doesNotAffectOther() {
        config.setInterval("12200", 120);
        assertEquals(DEFAULT_INTERVAL, config.getInterval("99999", DEFAULT_INTERVAL));
    }

    @Test
    void getInterval_defaultBelowMinimum_clampsToMinimum() {
        int result = config.getInterval("12200", 10);
        assertEquals(ScheduleConfig.MIN_INTERVAL_SECONDS, result);
    }

    @Test
    void getInterval_customBelowMinimum_clampsToMinimum() {
        config.setInterval("12200", 5);
        int result = config.getInterval("12200", DEFAULT_INTERVAL);
        assertEquals(ScheduleConfig.MIN_INTERVAL_SECONDS, result);
    }

    @Test
    void getInterval_exactlyMinimum_returnsMinimum() {
        config.setInterval("12200", ScheduleConfig.MIN_INTERVAL_SECONDS);
        assertEquals(ScheduleConfig.MIN_INTERVAL_SECONDS,
                config.getInterval("12200", DEFAULT_INTERVAL));
    }

    // -------------------------------------------------------------------------
    // setInterval
    // -------------------------------------------------------------------------

    @Test
    void setInterval_aboveMinimum_storesValue() {
        config.setInterval("12200", 600);
        assertEquals(600, config.getInterval("12200", DEFAULT_INTERVAL));
    }

    @Test
    void setInterval_belowMinimum_clampsToMinimum() {
        config.setInterval("12200", 1);
        assertEquals(ScheduleConfig.MIN_INTERVAL_SECONDS,
                config.getInterval("12200", DEFAULT_INTERVAL));
    }

    @Test
    void setInterval_overwritesPreviousValue() {
        config.setInterval("12200", 120);
        config.setInterval("12200", 600);
        assertEquals(600, config.getInterval("12200", DEFAULT_INTERVAL));
    }

    // -------------------------------------------------------------------------
    // resetInterval
    // -------------------------------------------------------------------------

    @Test
    void resetInterval_removesCustom_fallsBackToDefault() {
        config.setInterval("12200", 120);
        config.resetInterval("12200");
        assertEquals(DEFAULT_INTERVAL, config.getInterval("12200", DEFAULT_INTERVAL));
    }

    @Test
    void resetInterval_nonExistent_doesNotThrow() {
        assertDoesNotThrow(() -> config.resetInterval("99999"));
    }

    // -------------------------------------------------------------------------
    // hasCustomInterval
    // -------------------------------------------------------------------------

    @Test
    void hasCustomInterval_afterSet_returnsTrue() {
        config.setInterval("12200", 120);
        assertTrue(config.hasCustomInterval("12200"));
    }

    @Test
    void hasCustomInterval_beforeSet_returnsFalse() {
        assertFalse(config.hasCustomInterval("12200"));
    }

    @Test
    void hasCustomInterval_afterReset_returnsFalse() {
        config.setInterval("12200", 120);
        config.resetInterval("12200");
        assertFalse(config.hasCustomInterval("12200"));
    }

    // -------------------------------------------------------------------------
    // getAllCustomIntervals
    // -------------------------------------------------------------------------

    @Test
    void getAllCustomIntervals_returnsAllSetIntervals() {
        config.setInterval("12200", 120);
        config.setInterval("12385", 600);

        var all = config.getAllCustomIntervals();

        assertEquals(2, all.size());
        assertEquals(120, all.get("12200"));
        assertEquals(600, all.get("12385"));
    }

    @Test
    void getAllCustomIntervals_returnsCopy_modificationDoesNotAffectConfig() {
        config.setInterval("12200", 120);

        var copy = config.getAllCustomIntervals();
        copy.put("12200", 999);

        assertEquals(120, config.getInterval("12200", DEFAULT_INTERVAL));
    }

    @Test
    void getAllCustomIntervals_emptyConfig_returnsEmptyMap() {
        assertTrue(config.getAllCustomIntervals().isEmpty());
    }

    // -------------------------------------------------------------------------
    // save + load (round-trip)
    // -------------------------------------------------------------------------

    @Test
    void saveAndLoad_roundTrip_preservesIntervals() throws Exception {
        config.setInterval("12200", 120);
        config.setInterval("12385", 600);
        config.setInterval("150180180", 180);

        Path file = tempDir.resolve("schedule.json");
        config.save(file);

        ScheduleConfig loaded = ScheduleConfig.load(file);

        assertEquals(120, loaded.getInterval("12200", DEFAULT_INTERVAL));
        assertEquals(600, loaded.getInterval("12385", DEFAULT_INTERVAL));
        assertEquals(180, loaded.getInterval("150180180", DEFAULT_INTERVAL));
    }

    @Test
    void load_whenFileDoesNotExist_returnsEmptyConfig() {
        Path missing = tempDir.resolve("nonexistent.json");
        ScheduleConfig loaded = ScheduleConfig.load(missing);

        assertNotNull(loaded);
        assertTrue(loaded.getAllCustomIntervals().isEmpty());
    }

    @Test
    void load_whenFileIsBlank_returnsEmptyConfig() throws Exception {
        Path file = tempDir.resolve("blank.json");
        Files.writeString(file, "   ", StandardCharsets.UTF_8);

        ScheduleConfig loaded = ScheduleConfig.load(file);

        assertNotNull(loaded);
        assertTrue(loaded.getAllCustomIntervals().isEmpty());
    }

    @Test
    void load_whenFileIsInvalidJson_returnsEmptyConfig() throws Exception {
        Path file = tempDir.resolve("invalid.json");
        Files.writeString(file, "NOT JSON {{{", StandardCharsets.UTF_8);

        ScheduleConfig loaded = ScheduleConfig.load(file);

        assertNotNull(loaded);
        assertTrue(loaded.getAllCustomIntervals().isEmpty());
    }

    @Test
    void save_createsFileWithJsonContent() throws Exception {
        config.setInterval("12200", 300);

        Path file = tempDir.resolve("schedule.json");
        config.save(file);

        assertTrue(Files.exists(file));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("12200"));
        assertTrue(content.contains("300"));
    }
}