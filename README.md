# EcoAlerter IMGW

Aplikacja desktopowa (Swing, Java 21) do cyklicznego pobierania i monitorowania danych meteorologicznych i hydrologicznych z API IMGW. Wieloplatformowa — Windows, macOS, Linux.

---

## Szybki start

### 1. Zainstaluj Java JDK 21 lub nowsze

Sprawdź, czy działa:

```bash
java --version
# powinno pokazać "java version 21 lub openjdk 21" (lub nowsze)
```

### 2. Pobierz projekt z releases

### 3. Uruchom

**Windows / macOS / Linux:**

```bash
java -jar imgw-monitor.jar
```

---

## Funkcjonalności

- **Stacje:** dodawanie, usuwanie, edycja, aktywacja/dezaktywacja meteo i hydro.
- **Dane meteo:** temperatura, wiatr, opady — z dynamicznym wyborem widocznych kolumn.
- **Dane hydro:** stan wody, temperatura wody, przepływ, zjawiska (lód, zarastanie).
- **Ostrzeżenia IMGW:** filtrowanie po poziomie (żółty / pomarańczowy / czerwony), wizualizacja w GUI.
- **Harmonogram per stacja:** indywidualne interwały odpytywania, działa bez restartu po zmianach.
- **Persystencja:** plik JSON albo baza SQLite — przełączane w ustawieniach.
- **Powiadomienia:** wbudowane do paska statusu + banner offline gdy API nie odpowiada.

---

## Konfiguracja

Wszystkie ustawienia są edytowalne z GUI w zakładce „Ustawienia". Plik źródłowy: `app.properties`.

Najważniejsze klucze:

```properties
# FILE albo DATABASE
persistence.mode=file

# dane logowania do bazy danych (nie są wymagane w sqlite)
db.user= 
db.password=

# Poziom logowania w log4j2
log.level=INFO
```

Logowanie: `config/log4j2.xml` (Log4j2). Logi domyślnie idą do `logs/`.

---

## Stos technologiczny

| Komponent     | Wersja        |
| ------------- | ------------- |
| Java          | SE 21         |
| Build         | Maven         |
| Logowanie     | Log4j2        |
| JSON          | Gson          |
| Baza          | SQLite (JDBC) |
| Pula połączeń | HikariCP      |
| Testy         | JUnit 5       |

---

## Testy

```bash
mvn test         # Windows
```

---

## Licencja

MIT.
