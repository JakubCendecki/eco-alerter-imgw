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
 * Implementacja {@link DataRepository} zapisująca dane do relacyjnej bazy
 * danych przez JDBC.
 *
 * Korzysta z {@link ConnectionPool} (HikariCP). Każda operacja otwiera
 * i zamyka połączenie z puli przez try-with-resources, co gwarantuje
 * bezpieczeństwo wątkowe i zwolnienie zasobów nawet w razie wyjątku.
 *
 * Duplikaty są obsługiwane przez {@code INSERT OR IGNORE} (SQLite) lub
 * {@code ON CONFLICT DO NOTHING}, więc ponowne zapisanie tego samego pomiaru
 * jest bezpieczne i nie rzuca wyjątku.
 */
public class DatabaseRepository implements DataRepository {

    private static final Logger log = AppLogger.get(DatabaseRepository.class);

    private final ConnectionPool pool;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    /**
     * @param pool pula połączeń JDBC — typowo HikariCP skonfigurowane
     *             dla wybranego trybu bazy (SQLite/Postgres). Wstrzykiwane,
     *             żeby ten klasa nie musiał znać szczegółów konfiguracji.
     */
    public DatabaseRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    // =========================================================================
    // STACJE
    // =========================================================================

    /**
     * Zapisuje stację. Operacja upsert: gdy rekord o tym samym ID i typie
     * już istnieje, jego pola są aktualizowane, a {@code updated_at} ustawiane
     * na bieżący czas. Sprawdzanie biznesowe duplikatów (czy w ogóle wolno
     * zapisywać po raz drugi) odbywa się w
     * {@link ecoalerter.service.StationService}.
     */
    @Override
    public void saveStation(Station station) throws PersistenceException {
        String sql = """
                INSERT INTO stations (id, name, api_name, type, active, interval_seconds, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, strftime('%Y-%m-%d %H:%M:%S', 'now'))
                ON CONFLICT(id, type) DO UPDATE SET
                    name             = excluded.name,
                    api_name         = COALESCE(excluded.api_name, stations.api_name),
                    active           = excluded.active,
                    interval_seconds = excluded.interval_seconds,
                    updated_at       = excluded.updated_at
                """;

        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, station.getId());
            ps.setString(2, station.getName());
            ps.setString(3, station.getApiName());
            ps.setString(4, station.getType().name());
            ps.setInt(5, station.isActive() ? 1 : 0);
            ps.setInt(6, station.getIntervalSeconds());
            ps.executeUpdate();

            log.debug("Zapisano stację: {}", station.getId());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd zapisu stacji: " + station.getId(), e);
        }
    }

    /**
     * Usuwa wszystkie dane z bazy: pomiary meteo i hydro, ostrzeżenia oraz
     * same stacje. Ustawienia aplikacji nie są tknięte — leżą poza tym
     * repozytorium (w {@code app.properties}).
     *
     * Kolejność DELETE-ów ma znaczenie: najpierw tabele zależne
     * ({@code meteo_data}, {@code hydro_data}, {@code warnings}, które
     * referują {@code stations.id}), a dopiero na końcu sama tabela stacji.
     * Dzięki temu operacja przechodzi nawet jeśli w schemacie są zdefiniowane
     * ograniczenia FK między tabelami danych a tabelą stacji.
     *
     * Każdy DELETE używa osobnego połączenia z puli. Jeżeli któryś z nich
     * się nie powiedzie, wcześniejsze DELETE-y nie są cofane — czyszczenie
     * jest operacją idempotentną i wywoływaną ręcznie przez użytkownika
     * po dwuetapowym potwierdzeniu, więc ten kompromis jest świadomy.
     *
     * @throws PersistenceException gdy którakolwiek z operacji DELETE
     *                              zakończy się błędem SQL
     */
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
            
            sql = "DELETE FROM stations";
            try (Connection conn = pool.getConnection();
            	PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("Błąd czyszczenia danych o stacjach w DB", e);
            }

            log.info("Wyczyszczono wszystkie dane (DB)");
    	} catch (Exception e) {
    		throw new PersistenceException("Błąd czyszczenie danych w DB", e);
    	}
    	
    }
    
    /**
     * Usuwa stację o podanym ID i typie. Pomiary i ostrzeżenia powiązane
     * z tą stacją pozostają w bazie — kasowane są dopiero przy następnym
     * {@code deleteMeteoOlderThan} / {@code deleteHydroOlderThan} albo przy
     * {@link #clearAllData()}.
     */
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

    /** Wszystkie stacje (aktywne i nieaktywne), posortowane alfabetycznie po nazwie. */
    @Override
    public List<Station> findAllStations() throws PersistenceException {
        String sql = "SELECT id, name, api_name, type, active, interval_seconds FROM stations ORDER BY name";
        return queryStations(sql);
    }

    /** Aktywne stacje danego typu, posortowane alfabetycznie po nazwie. */
    @Override
    public List<Station> findActiveStations(StationType type) throws PersistenceException {
        String sql = """
                SELECT id, name, api_name, type, active, interval_seconds
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

    /**
     * Zapisuje pojedynczy pomiar meteo. Duplikat (ten sam {@code station_id +
     * timestamp}) jest cicho pomijany dzięki {@code INSERT OR IGNORE}.
     */
    @Override
    public void saveMeteo(MeteoData data) throws PersistenceException {
        String sql = """
                INSERT OR IGNORE INTO meteo_data
                    (station_id, timestamp, fetched_at, temperature, wind_speed, precipitation)
                VALUES (?, ?, ?, ?, ?, ?)
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

    /**
     * Zapisuje listę pomiarów meteo w jednej transakcji (batch).
     * Niepowodzenie któregokolwiek wstawienia powoduje rollback całej paczki
     * — albo wszystkie rekordy wpadają do bazy, albo żaden.
     */
    @Override
    public void saveAllMeteo(List<MeteoData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        String sql = """
                INSERT OR IGNORE INTO meteo_data
                    (station_id, timestamp, fetched_at, temperature, wind_speed, precipitation)
                VALUES (?, ?, ?, ?, ?, ?)
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

    /** Wszystkie pomiary meteo dla stacji, posortowane malejąco po czasie (najnowsze pierwsze). */
    @Override
    public List<MeteoData> findMeteoByStation(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, fetched_at, temperature, wind_speed, precipitation
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

    /** Pomiary meteo z zakresu [from, to] (inclusive), posortowane rosnąco po czasie. */
    @Override
    public List<MeteoData> findMeteoByStationAndRange(String stationId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, fetched_at, temperature, wind_speed, precipitation
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

    /**
     * Najnowszy pomiar meteo dla stacji albo empty gdy brak historii.
     * Realizowany przez {@code ORDER BY timestamp DESC LIMIT 1} — efektywne
     * przy odpowiednim indeksie na {@code (station_id, timestamp)}.
     */
    @Override
    public Optional<MeteoData> findLatestMeteo(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, fetched_at, temperature, wind_speed, precipitation
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

    /**
     * Usuwa rekordy meteo starsze niż {@code olderThan}.
     *
     * @return liczba usuniętych rekordów (zwracana przez JDBC)
     */
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

    /**
     * Zapisuje pojedynczy pomiar hydro. Duplikat ({@code station_id +
     * timestamp}) jest cicho pomijany.
     */
    @Override
    public void saveHydro(HydroData data) throws PersistenceException {
        String sql = """
                INSERT OR IGNORE INTO hydro_data
                    (station_id, timestamp, fetched_at, river_name,
                     water_level, water_temperature, flow,
                     ice_phenomenon, overgrowth_phenomenon)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    /**
     * Zapisuje listę pomiarów hydro w jednej transakcji (batch) — wszystko
     * albo nic, identyczna semantyka jak {@link #saveAllMeteo(List)}.
     */
    @Override
    public void saveAllHydro(List<HydroData> dataList) throws PersistenceException {
        if (dataList == null || dataList.isEmpty()) return;

        String sql = """
                INSERT OR IGNORE INTO hydro_data
                    (station_id, timestamp, fetched_at, river_name,
                     water_level, water_temperature, flow,
                     ice_phenomenon, overgrowth_phenomenon)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    /** Wszystkie pomiary hydro dla stacji, posortowane malejąco po czasie. */
    @Override
    public List<HydroData> findHydroByStation(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, fetched_at, river_name, water_level, water_temperature,
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

    /** Pomiary hydro z zakresu [from, to] (inclusive), posortowane rosnąco po czasie. */
    @Override
    public List<HydroData> findHydroByStationAndRange(String stationId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, fetched_at, river_name, water_level, water_temperature,
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

    /** Najnowszy pomiar hydro dla stacji albo empty gdy brak historii. */
    @Override
    public Optional<HydroData> findLatestHydro(String stationId) throws PersistenceException {
        String sql = """
                SELECT station_id, timestamp, fetched_at, river_name, water_level, water_temperature,
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

    /**
     * Usuwa rekordy hydro starsze niż {@code olderThan}.
     *
     * @return liczba usuniętych rekordów
     */
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

    /**
     * Zapisuje pojedyncze ostrzeżenie. Operacja upsert po kluczu {@code id} —
     * to samo ostrzeżenie wysłane ponownie przez IMGW (np. przedłużone
     * o godziny ważności) aktualizuje rekord zamiast tworzyć duplikat.
     */
    @Override
    public void saveWarning(Warning warning) throws PersistenceException {
        String sql = """
                INSERT INTO warnings
                    (id, station_id, level, type, phenomenon, probability, message, office, issued_at, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    level       = excluded.level,
                    message     = excluded.message,
                    office      = excluded.office,
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

    /**
     * Zapisuje listę ostrzeżeń. Iteruje przez {@link #saveWarning(Warning)} —
     * nie używa batchu, bo upsert z {@code ON CONFLICT DO UPDATE} na SQLite
     * nie zachowuje się dobrze w trybie batchowym (tracone są nowe wartości
     * po stronie {@code excluded}).
     */
    @Override
    public void saveAllWarnings(List<Warning> warnings) throws PersistenceException {
        if (warnings == null || warnings.isEmpty()) return;
        for (Warning w : warnings) {
            saveWarning(w);
        }
    }

    /**
     * Zwraca ostrzeżenia, których {@code valid_until} jest w przyszłości
     * (lub null = bezterminowe). Sortuje po poziomie (RED → ORANGE → YELLOW)
     * i czasie wystawienia.
     */
    @Override
    public List<Warning> findActiveWarnings() throws PersistenceException {
        String sql = """
                SELECT id, station_id, level, type, phenomenon, probability, message, office, issued_at, valid_until
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

    /**
     * Aktywne ostrzeżenia spełniające minimalny poziom. Filtrowanie odbywa
     * się w pamięci na wyniku {@link #findActiveWarnings()} — przy typowej
     * liczbie aktywnych ostrzeżeń (kilkanaście) szybsze niż osobne zapytanie.
     */
    @Override
    public List<Warning> findActiveWarningsByMinLevel(WarningLevel minLevel) throws PersistenceException {
        List<Warning> all = findActiveWarnings();
        if (minLevel == null) return all;
        return all.stream().filter(w -> w.meetsLevel(minLevel)).toList();
    }

    /**
     * Usuwa ostrzeżenia, których {@code valid_until} już minęło.
     *
     * @return liczba usuniętych rekordów
     */
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

    /**
     * Zamyka pulę połączeń. Wywoływać w shutdown hooku aplikacji
     * (po zamknięciu schedulera) — bez tego HikariCP może trzymać
     * połączenia, blokując zamknięcie procesu.
     */
    @Override
    public void close() {
        pool.close();
        log.info("DatabaseRepository zamknięty");
    }

    // =========================================================================
    // BINDOWANIE PARAMETRÓW (DRY)
    // =========================================================================

    /**
     * Wypełnia parametry PreparedStatement dla insertu meteo.
     * Kolejność zgodna z definicją kolumn:
     * {@code station_id, timestamp, fetched_at, temperature, wind_speed, precipitation}.
     */
    private void bindMeteo(PreparedStatement ps, MeteoData d) throws SQLException {
        ps.setString(1, d.getStationId());
        ps.setString(2, DateTimeUtil.toDbString(d.getTimestamp()));
        ps.setString(3, DateTimeUtil.toDbString(d.getFetchedAt()));
        setNullableDouble(ps, 4, d.getTemperature());
        setNullableDouble(ps, 5, d.getWindSpeed());
        setNullableDouble(ps, 6, d.getPrecipitation());
    }

    /**
     * Wypełnia parametry PreparedStatement dla insertu hydro.
     * Kolejność zgodna z definicją kolumn: {@code station_id, timestamp,
     * fetched_at, river_name, water_level, water_temperature, flow,
     * ice_phenomenon, overgrowth_phenomenon}.
     *
     * Zjawiska (lód, zarastanie) są intami 0/1 zamiast NULLABLE Boolean —
     * upraszcza filtrowanie po stronie SQL i zgadza się z tym, jak IMGW
     * zwraca te flagi.
     */
    private void bindHydro(PreparedStatement ps, HydroData d) throws SQLException {
        ps.setString(1, d.getStationId());
        ps.setString(2, DateTimeUtil.toDbString(d.getTimestamp()));
        ps.setString(3, DateTimeUtil.toDbString(d.getFetchedAt()));
        ps.setString(4, d.getRiverName());
        setNullableDouble(ps, 5, d.getWaterLevel());
        setNullableDouble(ps, 6, d.getWaterTemperature());
        setNullableDouble(ps, 7, d.getFlow());
        ps.setInt(8, d.getIcePhenomenon());
        ps.setInt(9, d.getOvergrowthPhenomenon());
    }

    /**
     * Wypełnia parametry PreparedStatement dla insertu ostrzeżenia.
     * {@code level} i {@code type} są zapisywane jako nazwy enum przez
     * {@code .name()} — przy odczycie wracają przez {@code fromString}.
     */
    private void bindWarning(PreparedStatement ps, Warning w) throws SQLException {
        ps.setString(1, w.getId());
        ps.setString(2, w.getStationId());
        ps.setString(3, w.getLevel() != null ? w.getLevel().name() : null);
        ps.setString(4, w.getType()  != null ? w.getType().name()  : null);
        ps.setString(5, w.getPhenomenon());
        ps.setInt   (6, w.getProbability());
        ps.setString(7, w.getMessage());
        ps.setString(8, w.getOffice());
        ps.setString(9, DateTimeUtil.toDbString(w.getIssuedAt()));
        ps.setString(10, DateTimeUtil.toDbString(w.getValidUntil()));
    }

    /**
     * Ustawia parametr typu Double — wartość null zapisuje jako SQL NULL
     * zamiast 0.0 (które {@code ps.setDouble(i, null)} nie obsługuje).
     */
    private void setNullableDouble(PreparedStatement ps, int index, Double value)
            throws SQLException {
        if (value != null) ps.setDouble(index, value);
        else               ps.setNull(index, java.sql.Types.REAL);
    }

    // =========================================================================
    // MAPOWANIE RESULTSET → MODEL
    // =========================================================================

    /**
     * Wykonuje proste zapytanie odczytujące stacje (bez parametrów) i mapuje
     * wynik na listę. Wydzielone z {@link #findAllStations()} jako szablon
     * dla podobnych zapytań, gdyby pojawiły się w przyszłości.
     */
    private List<Station> queryStations(String sql) throws PersistenceException {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            return mapStations(ps.executeQuery());

        } catch (SQLException e) {
            throw new PersistenceException("Błąd odczytu stacji", e);
        }
    }

    /** Mapuje wiersze ResultSet na listę {@link Station}. */
    private List<Station> mapStations(ResultSet rs) throws SQLException {
        List<Station> list = new ArrayList<>();
        while (rs.next()) {
            Station s = new Station();
            s.setId(rs.getString("id"));
            s.setName(rs.getString("name"));
            s.setApiName(rs.getString("api_name"));
            s.setType(StationType.fromString(rs.getString("type")));
            s.setActive(rs.getInt("active") == 1);
            s.setIntervalSeconds(rs.getInt("interval_seconds"));
            list.add(s);
        }
        return list;
    }

    /** Mapuje wiersze ResultSet na listę {@link MeteoData}. */
    private List<MeteoData> mapMeteoData(ResultSet rs) throws SQLException {
        List<MeteoData> list = new ArrayList<>();
        while (rs.next()) {
            MeteoData d = new MeteoData();
            d.setStationId(rs.getString("station_id"));
            d.setTimestamp(DateTimeUtil.parse(rs.getString("timestamp")).orElse(null));
            d.setFetchedAt(DateTimeUtil.parse(rs.getString("fetched_at")).orElse(null));
            d.setTemperature(getNullableDouble(rs, "temperature"));
            d.setWindSpeed(getNullableDouble(rs, "wind_speed"));
            d.setPrecipitation(getNullableDouble(rs, "precipitation"));
            list.add(d);
        }
        return list;
    }

    /** Mapuje wiersze ResultSet na listę {@link HydroData}. */
    private List<HydroData> mapHydroData(ResultSet rs) throws SQLException {
        List<HydroData> list = new ArrayList<>();
        while (rs.next()) {
            HydroData d = new HydroData();
            d.setStationId(rs.getString("station_id"));
            d.setTimestamp(DateTimeUtil.parse(rs.getString("timestamp")).orElse(null));
            d.setFetchedAt(DateTimeUtil.parse(rs.getString("fetched_at")).orElse(null));
            d.setRiverName(rs.getString("river_name"));
            d.setWaterLevel(getNullableDouble(rs, "water_level"));
            d.setWaterTemperature(getNullableDouble(rs, "water_temperature"));
            d.setFlow(getNullableDouble(rs, "flow"));
            d.setIcePhenomenon(rs.getInt("ice_phenomenon"));
            d.setOvergrowthPhenomenon(rs.getInt("overgrowth_phenomenon"));
            list.add(d);
        }
        return list;
    }

    /** Mapuje wiersze ResultSet na listę {@link Warning}. */
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
            w.setOffice(rs.getString("office"));
            w.setIssuedAt(DateTimeUtil.parse(rs.getString("issued_at")).orElse(null));
            w.setValidUntil(DateTimeUtil.parse(rs.getString("valid_until")).orElse(null));
            list.add(w);
        }
        return list;
    }

    /**
     * Czyta kolumnę Double z {@link ResultSet}, zwracając null gdy SQL NULL
     * — bez tego JDBC zwraca 0.0 i tracimy informację, że pomiar był pusty.
     */
    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double val = rs.getDouble(column);
        return rs.wasNull() ? null : val;
    }
}