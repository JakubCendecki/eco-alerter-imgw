-- =============================================================================
-- EcoAlerter IMGW — Schemat bazy danych
-- Kompatybilny z: SQLite 3.x, PostgreSQL 14+, MySQL 8+
-- Wykonywany automatycznie przez SchemaInitializer przy starcie aplikacji.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Stacje pomiarowe
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS stations (
    id               TEXT        NOT NULL,
    name             TEXT        NOT NULL,
    type             TEXT        NOT NULL CHECK (type IN ('METEO', 'HYDRO')),
    active           INTEGER     NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    interval_seconds INTEGER     NOT NULL DEFAULT 0 CHECK (interval_seconds >= 0),
    created_at       TEXT        NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now')),
    updated_at       TEXT        NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now')),
    PRIMARY KEY (id, type)
);

-- -----------------------------------------------------------------------------
-- Dane meteorologiczne
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS meteo_data (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id    TEXT    NOT NULL,
    timestamp     TEXT    NOT NULL,
    temperature   REAL,
    wind_speed    REAL,
    precipitation REAL,
    pressure      REAL,
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now')),
    FOREIGN KEY (station_id) REFERENCES stations (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);

-- Unikalne pomiary — stacja + czas (zapobiega duplikatom przy ponownym pobraniu)
CREATE UNIQUE INDEX IF NOT EXISTS idx_meteo_station_timestamp
    ON meteo_data (station_id, timestamp);

-- Indeks do szybkiego filtrowania po stacji i czasie (używany przez GUI i cleanup)
CREATE INDEX IF NOT EXISTS idx_meteo_station_id
    ON meteo_data (station_id);

CREATE INDEX IF NOT EXISTS idx_meteo_timestamp
    ON meteo_data (timestamp);

-- -----------------------------------------------------------------------------
-- Dane hydrologiczne
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS hydro_data (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id            TEXT    NOT NULL,
    timestamp             TEXT    NOT NULL,
    water_level           REAL,
    water_temperature     REAL,
    flow                  REAL,
    ice_phenomenon        INTEGER NOT NULL DEFAULT 0,
    overgrowth_phenomenon INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now')),
    FOREIGN KEY (station_id) REFERENCES stations (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_hydro_station_timestamp
    ON hydro_data (station_id, timestamp);

CREATE INDEX IF NOT EXISTS idx_hydro_station_id
    ON hydro_data (station_id);

CREATE INDEX IF NOT EXISTS idx_hydro_timestamp
    ON hydro_data (timestamp);

-- -----------------------------------------------------------------------------
-- Ostrzeżenia
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS warnings (
    id          TEXT    PRIMARY KEY,
    station_id  TEXT,
    level       TEXT    NOT NULL CHECK (level IN ('YELLOW', 'ORANGE', 'RED')),
    type        TEXT    NOT NULL CHECK (type  IN ('METEO', 'HYDRO')),
    phenomenon  TEXT,
    probability INTEGER NOT NULL DEFAULT -1,
    message     TEXT,
    issued_at   TEXT    NOT NULL,
    valid_until TEXT,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);

-- Indeks do filtrowania aktywnych ostrzeżeń (valid_until > now)
CREATE INDEX IF NOT EXISTS idx_warnings_valid_until
    ON warnings (valid_until);

-- Indeks do filtrowania po typie i poziomie (najczęstsze zapytania z GUI)
CREATE INDEX IF NOT EXISTS idx_warnings_level_type
    ON warnings (level, type);

-- -----------------------------------------------------------------------------
-- Metadane aplikacji (wersja schematu — do przyszłych migracji)
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app_metadata (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);

INSERT OR IGNORE INTO app_metadata (key, value) VALUES
    ('schema_version', '1'),
    ('created_at',     strftime('%Y-%m-%d %H:%M:%S', 'now'));