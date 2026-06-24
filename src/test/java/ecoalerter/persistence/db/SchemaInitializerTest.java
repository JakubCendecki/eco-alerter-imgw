package ecoalerter.persistence.db;

import ecoalerter.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe klasy SchemaInitializer.
 * Weryfikują że schema.sql tworzy wszystkie wymagane tabele.
 */
@ExtendWith(MockitoExtension.class)
class SchemaInitializerTest {

    @TempDir
    Path tempDir;

    @Mock
    AppConfig mockConfig;

    private ConnectionPool pool;

    @BeforeEach
    void setUp() {
        String dbPath = tempDir.resolve("schema_test.db").toString();
        when(mockConfig.getDbUrl()).thenReturn("jdbc:sqlite:" + dbPath);
        when(mockConfig.getDbUser()).thenReturn("");
        when(mockConfig.getDbPassword()).thenReturn("");
        when(mockConfig.getDbPoolMax()).thenReturn(1);
        when(mockConfig.getRaw("db.connection.timeout.ms")).thenReturn("");
        pool = new ConnectionPool(mockConfig);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    void initialize_createsStationsTable() throws Exception {
        new SchemaInitializer(pool).initialize();
        assertTrue(tableExists("stations"));
    }

    @Test
    void initialize_createsMeteoDataTable() throws Exception {
        new SchemaInitializer(pool).initialize();
        assertTrue(tableExists("meteo_data"));
    }

    @Test
    void initialize_createsHydroDataTable() throws Exception {
        new SchemaInitializer(pool).initialize();
        assertTrue(tableExists("hydro_data"));
    }

    @Test
    void initialize_createsWarningsTable() throws Exception {
        new SchemaInitializer(pool).initialize();
        assertTrue(tableExists("warnings"));
    }

    @Test
    void initialize_createsAppMetadataTable() throws Exception {
        new SchemaInitializer(pool).initialize();
        assertTrue(tableExists("app_metadata"));
    }

    @Test
    void initialize_isIdempotent_doesNotThrowOnSecondCall() throws Exception {
        SchemaInitializer initializer = new SchemaInitializer(pool);
        initializer.initialize();
        assertDoesNotThrow(initializer::initialize);
    }

    private boolean tableExists(String tableName) throws Exception {
        try (Connection conn = pool.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, tableName, new String[]{"TABLE"})) {
                return rs.next();
            }
        }
    }
}