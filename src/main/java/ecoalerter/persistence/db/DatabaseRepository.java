package ecoalerter.persistence.db;

import ecoalerter.model.HydroData;
import ecoalerter.model.MeteoData;
import ecoalerter.model.Station;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import ecoalerter.persistence.DataRepository;
import ecoalerter.persistence.PersistenceException;
import ecoalerter.util.AppLogger;
import ecoalerter.util.DateTimeUtil;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementacja DataRepository zapisująca dane do relacyjnej bazy danych przez JDBC.
 *
 * Korzysta z ConnectionPool (HikariCP). Każda operacja otwiera i zamyka połączenie
 * z puli, co gwarantuje bezpieczeństwo wątkowe.
 *
 * Duplikaty są obsługiwane przez INSERT OR IGNORE (SQLite) / ON CONFLICT DO NOTHING,
 * więc ponowne zapisanie tego samego pomiaru jest bezpieczne.
 */
public class DatabaseRepository implements DataRepository {

    private static final Logger log = AppLogger.get(DatabaseRepository.class);

    private final ConnectionPool pool;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public DatabaseRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    // =========================================================================
    // STACJE
    // =========================================================================

    @Override
    public void saveStation(Station station) throws PersistenceException {
        String sql = """
                INSERT INTO stations (id, name, type, active, interval_seconds, updated_at)
                VALUES (?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%S', 'now'))
                ON CONFLICT(id, type) DO UPDATE SET
                    name             = excluded.name,
                    active           = excluded.active,
                    interval_seconds = excluded.interval_seconds,
                    updated_at       = excluded.updated_at
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, station.getId());
            ps.setString(2, station.getName());
            ps.setString(3, station.getType().name());
            ps.setInt(4, station.isActive() ? 1 : 0);
            ps.setInt(5, station.getIntervalSeconds());
            ps.executeUpdate();

            log.debug("Zapisano stację: {}", station.getId());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd zapisu stacji: " + station.getId(), e);
        }
    }

    @Override
    public void clearAllData() throws PersistenceException {
    	try {
    		String sql = "DELETE FROM meteo_data";
            try (Connection conn = pool.getConnection();
            	PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("Błąd czyszczenia danych meteo w DB", e);
            }
            
            sql = "DELETE FROM hydro_data";
            try (Connection conn = pool.getConnection();
            	PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("Błąd czyszczenia danych hydro w DB", e);
            }
            
            sql = "DELETE FROM warnings";
            try (Connection conn = pool.getConnection();
            	PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("Błąd czyszczenia danych o ostrzeżeniach w DB", e);
            }

            log.info("Wyczyszczono wszystkie dane (DB)");
    	} catch (Exception e) {
    		throw new PersistenceException("Błąd czyszczenie danych w DB", e);
    	}
    	
    }
    
    @Override
    public void deleteStation(String stationId, StationType type) throws PersistenceException {
        String sql = "DELETE FROM stations WHERE id = ? AND type = ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            ps.setString(2, type.name());
            int deleted = ps.executeUpdate();
            log.info("Usunięto stację {} [{}], rekordów: {}", stationId, type, deleted);

        } catch (SQLException e) {
            throw new PersistenceException("Błąd usuwania stacji: " + stationId, e);
        }
    }

    @Override
    public List<Station> findAllStations() throws PersistenceException {
        String sql = "SELECT id, name, type, active, interval_seconds FROM stations ORDER BY name";
        return queryStations(sql);
    }

    @Override
    public List<Station> findActiveStations(StationType type) throws PersistenceException {
        String sql = """
                SELECT id, name, type, active, interval_seconds
                FROM stations
                WHERE active = 1 AND type = ?
                ORDER BY name
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, type.name());
            return mapStations(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu aktywnych stacji: " + type, e);
        }
    }

    // =========================================================================
    // DANE METEOROLOGICZNE
    // =========================================================================

    @Override
    public void saveMeteo(MeteoData data) throws PersistenceException {
        String sql = """
                INSERT OR IGNORE INTO meteo_data
                    (station_id, timestamp, temperature, wind_speed, precipitation)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindMeteo(ps, data);
            ps.executeUpdate();
            log.debug("Zapisano meteo: {} @ {}", data.getStationId(), data.getTimestamp());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd zapisu danych meteo: " + data.getStationId(), e);
        }
    }

    @Override
    public void saveAllMeteo(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        String sql = """
                INSERT OR IGNORE INTO meteo_data
                    (station_id, timestamp, temperature, wind_speed, precipitation)
                VALUES (?, ?, ?, ?, ?)
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            try {
                for (MeteoData data : dataList) {
                    bindMeteo(ps, data);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                log.debug("Batch zapis meteo: {} rekordów", dataList.size());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException e) {
            throw new PersistenceException("Błąd batch zapisu danych meteo", e);
        }
    }

    @Override
    public List<MeteoData> findMeteoByStation(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, temperature, wind_speed, precipitation
                FROM meteo_data
                WHERE station_id = ?
                ORDER BY timestamp DESC
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            return mapMeteoData(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu meteo dla stacji: " + stationId, e);
        }
    }

    @Override
    public List<MeteoData> findMeteoByStationAndRange(String stationId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, temperature, wind_speed, precipitation
                FROM meteo_data
                WHERE station_id = ? AND timestamp >= ? AND timestamp <= ?
                ORDER BY timestamp ASC
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            ps.setString(2, DateTimeUtil.toDbString(from));
            ps.setString(3, DateTimeUtil.toDbString(to));
            return mapMeteoData(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu meteo w zakresie dla: " + stationId, e);
        }
    }

    @Override
    public Optional<MeteoData> findLatestMeteo(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, temperature, wind_speed, precipitation
                FROM meteo_data
                WHERE station_id = ?
                ORDER BY timestamp DESC
                LIMIT 1
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            List<MeteoData> result = mapMeteoData(ps.executeQuery());
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu najnowszego pomiaru meteo: " + stationId, e);
        }
    }

    @Override
    public int deleteMeteoOlderThan(LocalDateTime olderThan) throws PersistenceException {
        String sql = "DELETE FROM meteo_data WHERE timestamp < ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, DateTimeUtil.toDbString(olderThan));
            int deleted = ps.executeUpdate();
            log.info("Usunięto {} starych rekordów meteo (przed {})", deleted, olderThan);
            return deleted;

        } catch (SQLException e) {
            throw new PersistenceException("Błąd czyszczenia starych danych meteo", e);
        }
    }

    // =========================================================================
    // DANE HYDROLOGICZNE
    // =========================================================================

    @Override
    public void saveHydro(HydroData data) throws PersistenceException {
        String sql = """
                INSERT OR IGNORE INTO hydro_data
                    (station_id, timestamp, water_level, water_temperature,
                     flow, ice_phenomenon, overgrowth_phenomenon)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindHydro(ps, data);
            ps.executeUpdate();
            log.debug("Zapisano hydro: {} @ {}", data.getStationId(), data.getTimestamp());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd zapisu danych hydro: " + data.getStationId(), e);
        }
    }

    @Override
    public void saveAllHydro(List<HydroData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        String sql = """
                INSERT OR IGNORE INTO hydro_data
                    (station_id, timestamp, water_level, water_temperature,
                     flow, ice_phenomenon, overgrowth_phenomenon)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            try {
                for (HydroData data : dataList) {
                    bindHydro(ps, data);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                log.debug("Batch zapis hydro: {} rekordów", dataList.size());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException e) {
            throw new PersistenceException("Błąd batch zapisu danych hydro", e);
        }
    }

    @Override
    public List<HydroData> findHydroByStation(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, water_level, water_temperature,
                       flow, ice_phenomenon, overgrowth_phenomenon
                FROM hydro_data
                WHERE station_id = ?
                ORDER BY timestamp DESC
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            return mapHydroData(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu hydro dla stacji: " + stationId, e);
        }
    }

    @Override
    public List<HydroData> findHydroByStationAndRange(String stationId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, water_level, water_temperature,
                       flow, ice_phenomenon, overgrowth_phenomenon
                FROM hydro_data
                WHERE station_id = ? AND timestamp >= ? AND timestamp <= ?
                ORDER BY timestamp ASC
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            ps.setString(2, DateTimeUtil.toDbString(from));
            ps.setString(3, DateTimeUtil.toDbString(to));
            return mapHydroData(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu hydro w zakresie dla: " + stationId, e);
        }
    }

    @Override
    public Optional<HydroData> findLatestHydro(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, water_level, water_temperature,
                       flow, ice_phenomenon, overgrowth_phenomenon
                FROM hydro_data
                WHERE station_id = ?
                ORDER BY timestamp DESC
                LIMIT 1
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stationId);
            List<HydroData> result = mapHydroData(ps.executeQuery());
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu najnowszego pomiaru hydro: " + stationId, e);
        }
    }

    @Override
    public int deleteHydroOlderThan(LocalDateTime olderThan) throws PersistenceException {
        String sql = "DELETE FROM hydro_data WHERE timestamp < ?";

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, DateTimeUtil.toDbString(olderThan));
            int deleted = ps.executeUpdate();
            log.info("Usunięto {} starych rekordów hydro (przed {})", deleted, olderThan);
            return deleted;

        } catch (SQLException e) {
            throw new PersistenceException("Błąd czyszczenia starych danych hydro", e);
        }
    }

    // =========================================================================
    // OSTRZEŻENIA
    // =========================================================================

    @Override
    public void saveWarning(Warning warning) throws PersistenceException {
        String sql = """
                INSERT INTO warnings
                    (id, station_id, level, type, phenomenon, probability, message, issued_at, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    level       = excluded.level,
                    message     = excluded.message,
                    valid_until = excluded.valid_until
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            bindWarning(ps, warning);
            ps.executeUpdate();
            log.debug("Zapisano ostrzeżenie: {} [{}]", warning.getId(), warning.getLevel());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd zapisu ostrzeżenia: " + warning.getId(), e);
        }
    }

    @Override
    public void saveAllWarnings(List<Warning> warnings) throws PersistenceException {
        if (warnings == null || warnings.isEmpty()) return;
        for (Warning w : warnings) {
            saveWarning(w);
        }
    }

    @Override
    public List<Warning> findActiveWarnings() throws PersistenceException {
        String sql = """
                SELECT id, station_id, level, type, phenomenon, probability, message, issued_at, valid_until
                FROM warnings
                WHERE valid_until IS NULL OR valid_until > ?
                ORDER BY
                    CASE level WHEN 'RED' THEN 1 WHEN 'ORANGE' THEN 2 ELSE 3 END,
                    issued_at DESC
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, DateTimeUtil.toDbString(LocalDateTime.now()));
            return mapWarnings(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu aktywnych ostrzeżeń", e);
        }
    }

    @Override
    public List<Warning> findActiveWarningsByMinLevel(WarningLevel minLevel) throws PersistenceException {
        List<Warning> all = findActiveWarnings();
        if (minLevel == null) return all;
        return all.stream().filter(w -> w.meetsLevel(minLevel)).toList();
    }

    @Override
    public int deleteExpiredWarnings() throws PersistenceException {
        String sql = """
                DELETE FROM warnings
                WHERE valid_until IS NOT NULL
                  AND valid_until <= ?
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, DateTimeUtil.toDbString(LocalDateTime.now()));
            int deleted = ps.executeUpdate();
            if (deleted > 0) log.info("Usunięto {} wygasłych ostrzeżeń", deleted);
            return deleted;

        } catch (SQLException e) {
            throw new PersistenceException("Błąd czyszczenia wygasłych ostrzeżeń", e);
        }
    }

    // =========================================================================
    // ZARZĄDZANIE ZASOBAMI
    // =========================================================================

    @Override
    public void close() {
        pool.close();
        log.info("DatabaseRepository zamknięty");
    }

    // =========================================================================
    // BINDOWANIE PARAMETRÓW (DRY)
    // =========================================================================

    private void bindMeteo(PreparedStatement ps, MeteoData d) throws SQLException {
        ps.setString(1, d.getStationId());
        ps.setString(2, DateTimeUtil.toDbString(d.getTimestamp()));
        setNullableDouble(ps, 3, d.getTemperature());
        setNullableDouble(ps, 4, d.getWindSpeed());
        setNullableDouble(ps, 5, d.getPrecipitation());
    }

    private void bindHydro(PreparedStatement ps, HydroData d) throws SQLException {
        ps.setString(1, d.getStationId());
        ps.setString(2, DateTimeUtil.toDbString(d.getTimestamp()));
        setNullableDouble(ps, 3, d.getWaterLevel());
        setNullableDouble(ps, 4, d.getWaterTemperature());
        setNullableDouble(ps, 5, d.getFlow());
        ps.setInt(6, d.getIcePhenomenon());
        ps.setInt(7, d.getOvergrowthPhenomenon());
    }

    private void bindWarning(PreparedStatement ps, Warning w) throws SQLException {
        ps.setString(1, w.getId());
        ps.setString(2, w.getStationId());
        ps.setString(3, w.getLevel() != null ? w.getLevel().name() : null);
        ps.setString(4, w.getType()  != null ? w.getType().name()  : null);
        ps.setString(5, w.getPhenomenon());
        ps.setInt   (6, w.getProbability());
        ps.setString(7, w.getMessage());
        ps.setString(8, DateTimeUtil.toDbString(w.getIssuedAt()));
        ps.setString(9, DateTimeUtil.toDbString(w.getValidUntil()));
    }

    private void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value != null) ps.setDouble(index, value);
        else               ps.setNull(index, java.sql.Types.REAL);
    }

    // =========================================================================
    // MAPOWANIE RESULTSET → MODEL
    // =========================================================================

    private List<Station> queryStations(String sql) throws PersistenceException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            return mapStations(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu stacji", e);
        }
    }

    private List<Station> mapStations(ResultSet rs) throws SQLException {
        List<Station> list = new ArrayList<>();
        while (rs.next()) {
            Station s = new Station();
            s.setId(rs.getString("id"));
            s.setName(rs.getString("name"));
            s.setType(StationType.fromString(rs.getString("type")));
            s.setActive(rs.getInt("active") == 1);
            s.setIntervalSeconds(rs.getInt("interval_seconds"));
            list.add(s);
        }
        return list;
    }

    private List<MeteoData> mapMeteoData(ResultSet rs) throws SQLException {
        List<MeteoData> list = new ArrayList<>();
        while (rs.next()) {
            MeteoData d = new MeteoData();
            d.setStationId(rs.getString("station_id"));
            d.setTimestamp(DateTimeUtil.parse(rs.getString("timestamp")).orElse(null));
            d.setTemperature(getNullableDouble(rs, "temperature"));
            d.setWindSpeed(getNullableDouble(rs, "wind_speed"));
            d.setPrecipitation(getNullableDouble(rs, "precipitation"));
            list.add(d);
        }
        return list;
    }

    private List<HydroData> mapHydroData(ResultSet rs) throws SQLException {
        List<HydroData> list = new ArrayList<>();
        while (rs.next()) {
            HydroData d = new HydroData();
            d.setStationId(rs.getString("station_id"));
            d.setTimestamp(DateTimeUtil.parse(rs.getString("timestamp")).orElse(null));
            d.setWaterLevel(getNullableDouble(rs, "water_level"));
            d.setWaterTemperature(getNullableDouble(rs, "water_temperature"));
            d.setFlow(getNullableDouble(rs, "flow"));
            d.setIcePhenomenon(rs.getInt("ice_phenomenon"));
            d.setOvergrowthPhenomenon(rs.getInt("overgrowth_phenomenon"));
            list.add(d);
        }
        return list;
    }

    private List<Warning> mapWarnings(ResultSet rs) throws SQLException {
        List<Warning> list = new ArrayList<>();
        while (rs.next()) {
            Warning w = new Warning();
            w.setId(rs.getString("id"));
            w.setStationId(rs.getString("station_id"));
            w.setLevel(WarningLevel.fromString(rs.getString("level")));
            w.setType(StationType.fromString(rs.getString("type")));
            w.setPhenomenon(rs.getString("phenomenon"));
            w.setProbability(rs.getInt("probability"));
            w.setMessage(rs.getString("message"));
            w.setIssuedAt(DateTimeUtil.parse(rs.getString("issued_at")).orElse(null));
            w.setValidUntil(DateTimeUtil.parse(rs.getString("valid_until")).orElse(null));
            list.add(w);
        }
        return list;
    }

    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double val = rs.getDouble(column);
        return rs.wasNull() ? null : val;
    }
}