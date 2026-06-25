-- =============================================================================
-- EcoAlerter IMGW — Schemat bazy danych
-- Kompatybilny z: SQLite 3.x, PostgreSQL 14+, MySQL 8+
-- Wykonywany automatycznie przez SchemaInitializer przy starcie aplikacji.
-- Uwaga: brak deklaracji FOREIGN KEY — integralność referencyjna zapewniana
-- przez warstwę serwisową, co upraszcza testy i eliminuje problemy z SQLite FK.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Stacje pomiarowe
-- PRIMARY KEY (id, type) — ta sama stacja IMGW może być typem METEO i HYDRO
-- api_name — oryginalna nazwa z API IMGW (nazwa_stacji dla meteo, stacja dla hydro)
-- name     — nazwa własna nadana przez użytkownika (edytowalna)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stations (
    id               TEXT    NOT NULL,
    name             TEXT    NOT NULL,
    api_name         TEXT,
    type             TEXT    NOT NULL CHECK (type IN ('METEO', 'HYDRO')),
    active           INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    interval_seconds INTEGER NOT NULL DEFAULT 0 CHECK (interval_seconds >= 0),
    created_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now')),
    updated_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now')),
    PRIMARY KEY (id, type)
);

CREATE INDEX IF NOT EXISTS idx_stations_type_active ON stations (type, active);

-- -----------------------------------------------------------------------------
-- Dane meteorologiczne
-- timestamp   — czas pomiaru po stronie IMGW
-- fetched_at  — czas pobrania rekordu przez aplikację (czas systemowy)
-- UNIQUE (station_id, timestamp) — brak duplikatów; fetched_at NIE wchodzi
-- do klucza unikalności, bo to ten sam pomiar IMGW, tylko pobrany przy innym
-- cyklu schedulera.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meteo_data (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id    TEXT    NOT NULL,
    timestamp     TEXT    NOT NULL,
    fetched_at    TEXT,
    temperature   REAL,
    wind_speed    REAL,
    precipitation REAL,
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_meteo_station_timestamp ON meteo_data (station_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_meteo_station_id ON meteo_data (station_id);
CREATE INDEX IF NOT EXISTS idx_meteo_timestamp  ON meteo_data (timestamp);
CREATE INDEX IF NOT EXISTS idx_meteo_fetched_at ON meteo_data (fetched_at);

-- -----------------------------------------------------------------------------
-- Dane hydrologiczne
-- timestamp   — czas pomiaru po stronie IMGW
-- fetched_at  — czas pobrania rekordu przez aplikację (czas systemowy)
-- river_name  — nazwa rzeki (pole "rzeka" z API)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS hydro_data (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id            TEXT    NOT NULL,
    timestamp             TEXT    NOT NULL,
    fetched_at            TEXT,
    river_name            TEXT,
    water_level           REAL,
    water_temperature     REAL,
    flow                  REAL,
    ice_phenomenon        INTEGER NOT NULL DEFAULT 0,
    overgrowth_phenomenon INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_hydro_station_timestamp ON hydro_data (station_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_hydro_station_id ON hydro_data (station_id);
CREATE INDEX IF NOT EXISTS idx_hydro_timestamp  ON hydro_data (timestamp);
CREATE INDEX IF NOT EXISTS idx_hydro_fetched_at ON hydro_data (fetched_at);

-- -----------------------------------------------------------------------------
-- Ostrzeżenia
-- valid_until NULL oznacza bezterminowe (zawsze aktywne)
-- office — biuro IMGW wystawiające ostrzeżenie (pole "biuro" w obu endpointach API)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS warnings (
    id          TEXT    PRIMARY KEY,
    station_id  TEXT,
    level       TEXT    NOT NULL CHECK (level IN ('YELLOW', 'ORANGE', 'RED')),
    type        TEXT    NOT NULL CHECK (type IN ('METEO', 'HYDRO')),
    phenomenon  TEXT,
    probability INTEGER NOT NULL DEFAULT -1,
    message     TEXT,
    office      TEXT,
    issued_at   TEXT    NOT NULL,
    valid_until TEXT,
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_warnings_valid_until ON warnings (valid_until);
CREATE INDEX IF NOT EXISTS idx_warnings_level_type  ON warnings (level, type);

-- -----------------------------------------------------------------------------
-- Migracje schematu (idempotentne — SchemaInitializer ignoruje "duplicate column")
-- Dla istniejących baz ze starszą wersją schematu — dokłada brakujące kolumny
-- bez naruszania istniejących danych.
-- -----------------------------------------------------------------------------

-- Migracja v1 → v2: kolumna office w warnings
ALTER TABLE warnings ADD COLUMN office TEXT;

-- Migracja v2 → v3: api_name w stations, fetched_at + river_name w danych
ALTER TABLE stations   ADD COLUMN api_name   TEXT;
ALTER TABLE meteo_data ADD COLUMN fetched_at TEXT;
ALTER TABLE hydro_data ADD COLUMN fetched_at TEXT;
ALTER TABLE hydro_data ADD COLUMN river_name TEXT;

-- -----------------------------------------------------------------------------
-- Metadane aplikacji (wersja schematu — do przyszłych migracji)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_metadata (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%S', 'now'))
);

-- Wstaw wersję dla nowej bazy (jeśli rekord nie istnieje)
INSERT OR IGNORE INTO app_metadata (key, value) VALUES
    ('schema_version', '3'),
    ('created_at',     strftime('%Y-%m-%d %H:%M:%S', 'now'));

-- Zaktualizuj wersję dla istniejących baz po migracji (idempotentne)
UPDATE app_metadata
   SET value = '3',
       updated_at = strftime('%Y-%m-%d %H:%M:%S', 'now')
 WHERE key = 'schema_version';