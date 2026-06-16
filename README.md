# IMGW Monitor

Wieloplatformowa aplikacja napisana w języku Java, służąca do automatycznego pobierania, monitorowania oraz przechowywania danych meteorologicznych i hydrologicznych udostępnianych przez interfejsy REST Instytutu Meteorologii i Gospodarki Wodnej (IMGW).

## Funkcjonalności

### Automatyczna integracja z API IMGW

* Cycliczne pobieranie danych meteorologicznych i hydrologicznych z interfejsów REST IMGW.
* Możliwość definiowania interwałów odpytywania przez użytkownika.
* Niezależne harmonogramowanie dla poszczególnych stacji pomiarowych.

### Zarządzanie stacjami

Moduł GUI umożliwiający zarządzanie monitorowanymi stacjami:

* Dodawanie nowych stacji meteorologicznych i hydrologicznych.
* Usuwanie istniejących stacji.
* Aktywowanie i dezaktywowanie wybranych stacji bez usuwania ich konfiguracji.
* Dynamiczna aktualizacja listy stacji.

### Konfiguracja zakresu danych

Użytkownik może określić, jakie informacje mają być monitorowane:

#### Dane meteorologiczne

* Temperatura powietrza
* Prędkość wiatru
* Ciśnienie atmosferyczne
* Opady atmosferyczne

#### Dane hydrologiczne

* Stan wody w rzekach
* Temperatura wody

#### Ostrzeżenia i alerty

* Ostrzeżenia meteorologiczne
* Ostrzeżenia hydrologiczne
* Filtrowanie i wizualizacja alertów

### Hybrydowy system persystencji danych

Sposób przechowywania danych jest kontrolowany za pomocą pliku konfiguracyjnego (`.properties`).

Obsługiwane metody zapisu:

#### Relacyjna baza danych

Zapisywanie zebranych danych w relacyjnym systemie zarządzania bazą danych.

#### System plików

Zapisywanie danych do ustrukturyzowanych plików:

* JSON
* CSV

### Harmonogram zadań

Elastyczny moduł planowania zadań umożliwiający definiowanie różnych częstotliwości odpytywania API dla poszczególnych lokalizacji.

### Powiadomienia i logowanie

* Wizualizacja stanów alarmowych w interfejsie GUI.
* Rejestrowanie zdarzeń aplikacji.
* Monitorowanie błędów i diagnostyka działania systemu.
* Konfigurowalne logowanie oparte o Log4j2.

### Wieloplatformowość

Aplikacja jest w pełni przenośna i działa na systemach:

* Windows
* Linux
* macOS

Niezależność od platformy została osiągnięta dzięki wykorzystaniu:

* Java SE 21
* Systemu budowania Maven
* Standardowych bibliotek JRE
* Abstrakcji ścieżek dostępu do zasobów

---

## Stos technologiczny

| Komponent           | Technologia |
| ------------------- | ----------- |
| Język programowania | Java SE 21  |
| System budowania    | Maven       |
| Testowanie          | JUnit       |
| Logowanie           | Log4j2      |
| Obsługa JSON        | Gson        |

---

## Struktura projektu

```text
eco-alerter-imgw/
├── config/
├── data/
│   ├── hydro/
│   └── meteo/
├── docs/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── ecoalerter/
│   │   │       ├── api/
│   │   │       ├── config/
│   │   │       ├── gui/
│   │   │       ├── model/
│   │   │       ├── persistence/
│   │   │       ├── scheduler/
│   │   │       ├── service/
│   │   │       └── util/
│   │   └── resources/
│   │       └── icons/
│   └── test/
│       └── java/
│           └── ecoalerter/
│               ├── api/
│               ├── persistence/
│               ├── scheduler/
│               └── service/
└── target/
```

---

## Wymagania

### Środowisko uruchomieniowe

* Java 21 lub nowsza

Sprawdzenie instalacji:

```bash
java --version
```

### Narzędzia budowania

* Maven 3.9 lub nowszy

Sprawdzenie instalacji:

```bash
mvn --version
```

---

## Konfiguracja

Ustawienia aplikacji przechowywane są w pliku:

```properties
config/app.properties
```

Przykładowa konfiguracja:

```properties
# Źródło danych
imgw.api.enabled=true

# Harmonogram
scheduler.default.interval=300

# Tryb persystencji
persistence.mode=file

# Dostępne wartości:
# file
# database

# Format plików
file.format=json

# Dostępne wartości:
# json
# csv
```

---

## Budowanie projektu

Sklonuj repozytorium:

```bash
git clone https://github.com/JakubCendecki/eco-alerter-imgw
cd eco-alerter-imgw
```

Skompiluj projekt:

```bash
mvn clean compile
```

Uruchom testy:

```bash
mvn test
```

Utwórz pakiet wykonywalny:

```bash
mvn clean package
```

Wygenerowane artefakty będą dostępne w katalogu:

```text
target/
```

---

## Uruchamianie aplikacji

### Za pomocą Maven

```bash
mvn exec:java
```

### Za pomocą wygenerowanego pliku JAR

```bash
java -jar target/imgw-monitor.jar
```

---

## Logowanie

Aplikacja wykorzystuje bibliotekę Log4j2.

Plik konfiguracyjny:

```text
config/log4j2.xml
```

Przykładowe rejestrowane zdarzenia:

* Żądania wysyłane do API IMGW
* Aktywność harmonogramu zadań
* Operacje zapisu danych
* Pobieranie ostrzeżeń i alertów
* Wyjątki występujące podczas działania aplikacji

---

## Testowanie

Uruchomienie wszystkich testów:

```bash
mvn test
```

Generowanie raportów testowych:

```bash
mvn surefire-report:report
```

---

## Licencja

Projekt jest udostępniany na licencji MIT.
