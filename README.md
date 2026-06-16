# 🌿 EcoAlerter IMGW

> System monitorowania i rejestracji danych środowiskowych oparty na publicznym API Instytutu Meteorologii i Gospodarki Wodnej (IMGW-PIB).

![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk) ![Maven](https://img.shields.io/badge/Build-Maven-red?logo=apachemaven) ![License](https://img.shields.io/badge/License-MIT-green) ![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux-blue)

---

## Spis treści

- [🌿 EcoAlerter IMGW](#-ecoalerter-imgw)
  - [Spis treści](#spis-treści)
  - [Opis projektu](#opis-projektu)
  - [Funkcjonalności](#funkcjonalności)
    - [🌐 Komunikacja z API IMGW](#-komunikacja-z-api-imgw)
    - [📍 Zarządzanie lokalizacjami](#-zarządzanie-lokalizacjami)
    - [📊 Zakres monitorowanych danych](#-zakres-monitorowanych-danych)
    - [💾 Hybrydowy system persystencji](#-hybrydowy-system-persystencji)
    - [⏱️ Harmonogram zadań](#️-harmonogram-zadań)
    - [🔔 System powiadomień](#-system-powiadomień)
  - [Wymagania systemowe](#wymagania-systemowe)
  - [Instalacja i uruchomienie](#instalacja-i-uruchomienie)
    - [1. Klonowanie repozytorium](#1-klonowanie-repozytorium)
    - [2. Budowanie (fat JAR)](#2-budowanie-fat-jar)
    - [3. Uruchomienie](#3-uruchomienie)
    - [4. Uruchomienie z własnym plikiem konfiguracyjnym](#4-uruchomienie-z-własnym-plikiem-konfiguracyjnym)
  - [Konfiguracja](#konfiguracja)
  - [Struktura projektu](#struktura-projektu)
  - [API IMGW — używane endpointy](#api-imgw--używane-endpointy)
  - [Tryby persystencji](#tryby-persystencji)
    - [Tryb plikowy (`persistence.mode=FILE`)](#tryb-plikowy-persistencemodefile)
    - [Tryb bazodanowy (`persistence.mode=DATABASE`)](#tryb-bazodanowy-persistencemodedatabase)
  - [Schemat bazy danych](#schemat-bazy-danych)
  - [Budowanie projektu](#budowanie-projektu)
    - [Zależności Maven (główne)](#zależności-maven-główne)
  - [Uruchamianie testów](#uruchamianie-testów)
  - [Wieloplatformowość](#wieloplatformowość)
    - [Testowane konfiguracje](#testowane-konfiguracje)
  - [Licencja](#licencja)

---

## Opis projektu

**EcoAlerter IMGW** to desktopowa aplikacja Java umożliwiająca automatyczne pobieranie, rejestrowanie i wizualizację danych meteorologicznych oraz hydrologicznych z publicznego REST API IMGW-PIB. Aplikacja pozwala na monitorowanie wybranych stacji pomiarowych w zdefiniowanych interwałach czasowych, przechowywanie danych lokalnie (w bazie danych lub plikach) oraz natychmiastowe powiadamianie użytkownika o aktywnych ostrzeżeniach pogodowych i hydrologicznych.

Projekt jest w pełni wieloplatformowy — działa na systemach Windows, Linux i macOS bez żadnych dodatkowych zależności środowiskowych poza standardowym JRE.

---

## Funkcjonalności

### 🌐 Komunikacja z API IMGW

- Automatyczne pobieranie danych z REST API IMGW-PIB w odstępach czasowych definiowanych per stacja
- Obsługa danych meteorologicznych i hydrologicznych
- Pobieranie i filtrowanie bieżących ostrzeżeń pogodowych i hydrologicznych

### 📍 Zarządzanie lokalizacjami

- Graficzny interfejs do dodawania i usuwania stacji pomiarowych
- Aktywacja / dezaktywacja wybranych stacji bez restartu aplikacji
- Obsługa stacji typu METEO i HYDRO

### 📊 Zakres monitorowanych danych

| Typ | Parametry |
| --- | --------- |
| **Meteo** | Temperatura powietrza, prędkość wiatru, opady, ciśnienie atmosferyczne |
| **Hydro** | Stan wody na rzekach, temperatura wody |
| **Ostrzeżenia** | Alerty pogodowe i hydrologiczne z filtrowaniem po poziomie (YELLOW / ORANGE / RED) |

### 💾 Hybrydowy system persystencji

- Sterowany z pliku `app.properties` — bez zmiany kodu
- **Tryb bazodanowy**: zapis do SQLite (domyślnie) lub innej relacyjnej bazy danych przez JDBC
- **Tryb plikowy**: zapis do ustrukturyzowanych plików JSON lub CSV

### ⏱️ Harmonogram zadań

- Niezależne interwały odpytywania API dla każdej stacji
- Dynamiczna zmiana interwałów bez restartu aplikacji
- Oparty na `ScheduledExecutorService` z JDK (brak zewnętrznych zależności)

### 🔔 System powiadomień

- Wizualizacja alertów w dedykowanym panelu GUI
- Oznaczenie poziomu ostrzeżenia kolorem (żółty / pomarańczowy / czerwony)
- Pasek statusu z informacją o aktywnych stacjach i ostatniej synchronizacji

---

## Wymagania systemowe

| Komponent | Minimalna wersja |
| --------- | ---------------- |
| Java (JRE / JDK) | 17+ |
| Maven (do budowania) | 3.8+ |
| System operacyjny | Windows 10+, Ubuntu 20.04+ |
| Połączenie z Internetem | Wymagane (API IMGW) |
| Miejsce na dysku | ~50 MB (aplikacja) + dane |

---

## Instalacja i uruchomienie

### 1. Klonowanie repozytorium

```bash
git clone https://github.com/JakubCendecki/eco-alerter-imgw.git
cd eco-alerter-imgw
```

### 2. Budowanie (fat JAR)

```bash
mvn clean package -DskipTests
```

Plik wykonywalny zostanie utworzony w katalogu `target/`:

```bash
target/eco-alerter-imgw-1.0.0-jar-with-dependencies.jar
```

### 3. Uruchomienie

```bash
java -jar target/eco-alerter-imgw-1.0.0-jar-with-dependencies.jar
```

Przy pierwszym uruchomieniu aplikacja automatycznie:

- Tworzy katalog `data/` na dane lokalne
- Kopiuje domyślny plik `app.properties` do katalogu roboczego
- Inicjalizuje schemat bazy danych (jeśli tryb `DATABASE`)

### 4. Uruchomienie z własnym plikiem konfiguracyjnym

```bash
java -jar eco-alerter-imgw.jar --config /ścieżka/do/app.properties
```

---

## Konfiguracja

Plik `app.properties` steruje zachowaniem całej aplikacji. Domyślna lokalizacja to katalog roboczy aplikacji.

```properties
# ============================================================
# PERSYSTENCJA
# ============================================================

# Tryb zapisu danych: FILE lub DATABASE
persistence.mode=FILE

# --- Tryb plikowy ---
# Format zapisu: JSON lub CSV
storage.file.format=JSON
# Katalog docelowy (ścieżka względna lub bezwzględna)
storage.file.dir=./data

# --- Tryb bazodanowy ---
# Aktywny gdy persistence.mode=DATABASE
db.url=jdbc:sqlite:./data/ecoalerter.db
db.user=
db.password=
# Maksymalna liczba połączeń w puli (HikariCP)
db.pool.max=5

# ============================================================
# API IMGW
# ============================================================

api.imgw.base.url=https://danepubliczne.imgw.pl/api/data
# Timeout żądania HTTP w sekundach
api.timeout.seconds=10
# Liczba prób ponowienia w przypadku błędu
api.retry.count=3

# ============================================================
# HARMONOGRAM ZADAŃ
# ============================================================

# Domyślny interwał odpytywania API (w sekundach)
# Można nadpisać per stacja w GUI
scheduler.default.interval.seconds=300

# ============================================================
# OSTRZEŻENIA
# ============================================================

warnings.enabled=true
# Minimalny poziom alertu do wyświetlenia: YELLOW, ORANGE, RED
warnings.filter.level=YELLOW

# ============================================================
# LOGOWANIE
# ============================================================

# Poziom logów: TRACE, DEBUG, INFO, WARN, ERROR
log.level=INFO
log.file.enabled=true
log.file.dir=./logs
```

---

## Struktura projektu

```text
eco-alerter-imgw/
├── config/                        # Konfiguracja dostarczana z projektem
│   └── app.properties
├── data/                          # Dane lokalne (tworzony automatycznie)
│   ├── meteo/
│   └── hydro/
├── docs/                          # Dokumentacja
│   ├── architecture.md
│   ├── api-reference.md
│   └── setup.md
├── logs/                          # Logi aplikacji (tworzony automatycznie)
├── src/
│   ├── main/
│   │   ├── java/pl/ecoalerter/
│   │   │   ├── EcoAlerterApp.java         # Punkt wejścia (main)
│   │   │   ├── config/                    # Ładowanie konfiguracji
│   │   │   ├── model/                     # Modele danych (Station, MeteoData, …)
│   │   │   ├── api/                       # Klient HTTP i serwisy IMGW
│   │   │   ├── scheduler/                 # Harmonogram zadań
│   │   │   ├── persistence/               # Zapis danych (DB i pliki)
│   │   │   │   ├── db/
│   │   │   │   └── file/
│   │   │   ├── service/                   # Logika biznesowa
│   │   │   ├── gui/                       # Interfejs graficzny (Swing)
│   │   │   │   ├── panels/
│   │   │   │   ├── dialogs/
│   │   │   │   └── components/
│   │   │   └── util/                      # Narzędzia pomocnicze
│   │   └── resources/
│   │       ├── app.properties             # Domyślna konfiguracja
│   │       ├── schema.sql                 # DDL bazy danych
│   │       ├── logback.xml
│   │       └── icons/
│   └── test/
│       └── java/pl/ecoalerter/
│           ├── api/
│           ├── persistence/
│           ├── scheduler/
│           └── service/
├── .github/workflows/build.yml    # CI/CD
├── pom.xml
├── README.md
└── CHANGELOG.md
```

---

## API IMGW — używane endpointy

Aplikacja korzysta wyłącznie z publicznego, bezpłatnego API IMGW-PIB. Nie jest wymagany żaden klucz API ani rejestracja.

| Zasób | Endpoint |
| ----- | -------- |
| Dane meteo — wszystkie stacje | `GET /api/data/synop` |
| Dane meteo — konkretna stacja | `GET /api/data/synop/id/{station_id}` |
| Dane hydro — wszystkie stacje | `GET /api/data/hydro` |
| Dane hydro — konkretna stacja | `GET /api/data/hydro/id/{station_id}` |

Pełna dokumentacja endpointów dostępna jest w [`docs/api-reference.md`](docs/api-reference.md).

> **Uwaga:** API IMGW zwraca dane w formacie JSON. Aplikacja automatycznie deserializuje odpowiedzi do wewnętrznych modeli danych.

---

## Tryby persystencji

### Tryb plikowy (`persistence.mode=FILE`)

Dane zapisywane są do plików w katalogu `storage.file.dir`. Dla każdej stacji tworzony jest osobny podkatalog.

**Przykład struktury (format JSON):**

```text
data/
├── meteo/
│   ├── 12200_WARSZAWA.json
│   └── 12385_KRAKÓW.json
└── hydro/
    └── 150180180_WISŁA_WARSZAWA.json
```

**Przykładowy rekord JSON:**

```json
{
  "stationId": "12200",
  "stationName": "WARSZAWA",
  "timestamp": "2025-06-14T12:00:00",
  "temperature": 22.4,
  "windSpeed": 3.1,
  "precipitation": 0.0,
  "pressure": 1013.2
}
```

### Tryb bazodanowy (`persistence.mode=DATABASE`)

Domyślnie używana jest baza SQLite — nie wymaga instalacji żadnego serwera. Wystarczy podać ścieżkę do pliku `.db` w konfiguracji.

Aby użyć PostgreSQL lub MySQL, należy zmienić `db.url` i dodać odpowiedni driver JDBC do `pom.xml`.

---

## Schemat bazy danych

```sql
-- Stacje pomiarowe
CREATE TABLE stations (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    type             TEXT NOT NULL,      -- 'METEO' | 'HYDRO'
    active           INTEGER DEFAULT 1,
    interval_seconds INTEGER DEFAULT 300
);

-- Dane meteorologiczne
CREATE TABLE meteo_data (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id   TEXT NOT NULL REFERENCES stations(id),
    timestamp    TEXT NOT NULL,
    temperature  REAL,
    wind_speed   REAL,
    precipitation REAL,
    pressure     REAL
);

-- Dane hydrologiczne
CREATE TABLE hydro_data (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id        TEXT NOT NULL REFERENCES stations(id),
    timestamp         TEXT NOT NULL,
    water_level       REAL,
    water_temperature REAL
);

-- Ostrzeżenia
CREATE TABLE warnings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    station_id  TEXT,
    level       TEXT NOT NULL,   -- 'YELLOW' | 'ORANGE' | 'RED'
    type        TEXT NOT NULL,   -- 'METEO' | 'HYDRO'
    message     TEXT,
    issued_at   TEXT NOT NULL,
    valid_until TEXT
);
```

---

## Budowanie projektu

```bash
# Czyszczenie i kompilacja
mvn clean compile

# Uruchomienie testów
mvn test

# Budowanie fat JAR (ze wszystkimi zależnościami)
mvn clean package

# Pominięcie testów przy budowaniu
mvn clean package -DskipTests

# Generowanie raportu z testów i pokrycia kodu
mvn verify
```

### Zależności Maven (główne)

| Biblioteka | Wersja | Cel |
| ---------- | ------ | --- |
| `org.slf4j:slf4j-api` | 2.0+ | API logowania |
| `ch.qos.logback:logback-classic` | 1.4+ | Implementacja logowania |
| `com.google.code.gson:gson` | 2.10+ | Parsowanie JSON |
| `com.zaxxer:HikariCP` | 5.0+ | Pula połączeń JDBC |
| `org.xerial:sqlite-jdbc` | 3.45+ | Driver SQLite |
| `com.opencsv:opencsv` | 5.9+ | Zapis CSV |
| `org.junit.jupiter:junit-jupiter` | 5.10+ | Testy jednostkowe |
| `org.mockito:mockito-core` | 5.0+ | Mockowanie w testach |

---

## Uruchamianie testów

```bash
# Wszystkie testy
mvn test

# Konkretna klasa testowa
mvn test -Dtest=MeteoApiServiceTest

# Konkretna metoda
mvn test -Dtest=MeteoApiServiceTest#shouldParseApiResponse

# Testy z raportem HTML (target/site/surefire-report.html)
mvn surefire-report:report
```

Testy jednostkowe używają zamockowanego klienta HTTP — nie wymagają połączenia z API IMGW. Testy integracyjne persystencji używają in-memory SQLite.

---

## Wieloplatformowość

Aplikacja zapewnia pełną przenośność dzięki:

- **`PathResolver`** — wszystkie ścieżki do plików i katalogów są budowane programatycznie przez `java.nio.file.Paths`, bez hardcodowanych separatorów (`/` vs `\`)
- **Konfigurowalny katalog danych** — ścieżka do `data/` i `logs/` pochodzi z `app.properties`, domyślnie względna wobec katalogu uruchomienia
- **Brak natywnych zależności** — GUI oparte na Java Swing (wbudowane w JRE), klient HTTP z `java.net.http` (Java 11+), baza SQLite jako plik lokalny
- **Jawna obsługa `file.encoding`** — wszystkie operacje I/O używają kodowania UTF-8 przez `StandardCharsets.UTF_8`

### Testowane konfiguracje

| System | JDK | Status |
| ------ | --- | ------ |
| Windows 11 | OpenJDK 17, 21 | ✅ |
| Ubuntu 22.04 | OpenJDK 17, 21 | ✅ |

---

## Licencja

Projekt udostępniany na licencji [MIT](LICENSE).

Dane pogodowe i hydrologiczne pochodzą z publicznego API **IMGW-PIB** (Instytut Meteorologii i Gospodarki Wodnej — Państwowy Instytut Badawczy) i są dostępne bezpłatnie na stronie [danepubliczne.imgw.pl](https://danepubliczne.imgw.pl).

---

<p style="text-align:center;">
  Zbudowane z ☕ i Java &nbsp;|&nbsp; Dane © IMGW-PIB
</p>
