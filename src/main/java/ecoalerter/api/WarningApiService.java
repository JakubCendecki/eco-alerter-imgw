package ecoalerter.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serwis pobierający, parsujący i filtrujący ostrzeżenia pogodowe
 * oraz hydrologiczne z API IMGW-PIB.
 *
 * Korzysta z endpointów ApiEndpoints.WARNINGS_METEO oraz ApiEndpoints.WARNINGS_HYDRO.
 *
 * UWAGA — oba endpointy zwracają zupełnie różne nazwy pól dla analogicznych
 * koncepcji, dlatego parsowanie meteo i hydro jest rozdzielone na dwie
 * niezależne metody (parseMeteoObject / parseHydroObject), a nie wspólny
 * parser z listą alternatywnych nazw pól.
 *
 * Rzeczywista odpowiedź /warningsmeteo (przykład):
 *   id, nazwa_zdarzenia, stopien, prawdopodobienstwo,
 *   obowiazuje_od, obowiazuje_do, opublikowano, tresc, komentarz, biuro, teryt[]
 *
 * Rzeczywista odpowiedź /warningshydro (przykład):
 *   numer (NIE jest globalnie unikalny — brak pola "id"),
 *   stopień (z polskim znakiem ń — inna nazwa niż w meteo!),
 *   prawdopodobienstwo, data_od, data_do, opublikowano,
 *   zdarzenie, przebieg, komentarz, biuro, obszary[]
 *
 * Żadny z dwóch endpointów nie udostępnia identyfikatora konkretnej stacji
 * pomiarowej — ostrzeżenia są regionalne (kody TERYT dla meteo, zlewnie/województwa
 * dla hydro), więc Warning.stationId jest zawsze null dla obu typów.
 *
 * Gdy brak aktywnych ostrzeżeń, API może zwrócić pustą tablicę [] albo,
 * w niektórych przypadkach, obiekt {"status":false,"message":"..."} —
 * oba warianty są tu obsługiwane jako brak ostrzeżeń, nie jako błąd parsowania.
 */
public class WarningApiService {

    private static final Logger log = LogManager.getLogger(WarningApiService.class);

    /** Format daty używany przez oba endpointy IMGW — zawiera sekundy. */
    private static final DateTimeFormatter API_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Rok używany przez IMGW jako sentinel "bezterminowo" (np. "9999-12-31 23:59:59"). */
    private static final int INDEFINITE_YEAR_SENTINEL = 9999;

    private final ImgwApiClient apiClient;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public WarningApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Pobiera wszystkie bieżące ostrzeżenia meteorologiczne.
     *
     * @return lista ostrzeżeń; pusta lista gdy brak alertów lub błąd parsowania
     * @throws ApiException gdy żądanie HTTP nie powiedzie się
     */
    public List<Warning> fetchMeteoWarnings() throws ApiException {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.WARNINGS_METEO);
        log.info("Pobieranie ostrzeżeń meteo: {}", url);

        try {
            String json = apiClient.get(url);
            return parseWarningsArray(json, StationType.METEO);
        } catch (ApiException e) {
            if (e.isNotFound()) {
                log.info("Brak aktywnych ostrzeżeń meteo (404)");
                return Collections.emptyList();
            }
            throw e;
        }
    }

    /**
     * Pobiera wszystkie bieżące ostrzeżenia hydrologiczne.
     *
     * @return lista ostrzeżeń; pusta lista gdy brak alertów lub błąd parsowania
     * @throws ApiException gdy żądanie HTTP nie powiedzie się
     */
    public List<Warning> fetchHydroWarnings() throws ApiException {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.WARNINGS_HYDRO);
        log.info("Pobieranie ostrzeżeń hydro: {}", url);

        try {
            String json = apiClient.get(url);
            return parseWarningsArray(json, StationType.HYDRO);
        } catch (ApiException e) {
            if (e.isNotFound()) {
                log.info("Brak aktywnych ostrzeżeń hydro (404)");
                return Collections.emptyList();
            }
            throw e;
        }
    }

    /**
     * Pobiera ostrzeżenia obu typów (meteo + hydro) w jednym wywołaniu.
     * Błąd jednego typu nie blokuje pobrania drugiego.
     *
     * @return połączona lista wszystkich aktywnych ostrzeżeń
     * @throws ApiException gdy oba żądania nie powiodą się
     */
    public List<Warning> fetchAllWarnings() throws ApiException {
        List<Warning> meteo = Collections.emptyList();
        List<Warning> hydro = Collections.emptyList();
        ApiException lastError = null;

        try {
            meteo = fetchMeteoWarnings();
        } catch (ApiException e) {
            log.warn("Nie udało się pobrać ostrzeżeń meteo: {}", e.getMessage());
            lastError = e;
        }

        try {
            hydro = fetchHydroWarnings();
        } catch (ApiException e) {
            log.warn("Nie udało się pobrać ostrzeżeń hydro: {}", e.getMessage());
            if (lastError != null) {
                throw new ApiException("Nie udało się pobrać żadnych ostrzeżeń", e);
            }
            lastError = e;
        }

        List<Warning> all = new ArrayList<>();
        all.addAll(meteo);
        all.addAll(hydro);

        log.info("Pobrano łącznie {} ostrzeżeń (meteo={}, hydro={})",
                all.size(), meteo.size(), hydro.size());
        return all;
    }

    /**
     * Filtruje listę ostrzeżeń — zwraca tylko te o poziomie >= minLevel.
     *
     * @param warnings lista do filtrowania
     * @param minLevel minimalny poziom alertu (włącznie)
     * @return przefiltrowana lista
     */
    public List<Warning> filterByMinLevel(List<Warning> warnings, WarningLevel minLevel) {
        if (warnings == null || warnings.isEmpty()) return Collections.emptyList();
        if (minLevel == null) return new ArrayList<>(warnings);

        return warnings.stream()
                .filter(w -> w.getLevel() != null && w.getLevel().ordinal() >= minLevel.ordinal())
                .toList();
    }

    /**
     * Filtruje ostrzeżenia dotyczące konkretnej stacji.
     * Ponieważ API IMGW nie przypisuje ostrzeżeń do stacji pomiarowych
     * (są regionalne), stationId w Warning jest zawsze null — metoda
     * zwraca wtedy wszystkie ostrzeżenia bez filtrowania.
     *
     * @param warnings  lista ostrzeżeń
     * @param stationId ID stacji
     * @return ostrzeżenia powiązane ze stacją lub ogólne (stationId == null)
     */
    public List<Warning> filterByStation(List<Warning> warnings, String stationId) {
        if (stationId == null || stationId.isBlank()) return new ArrayList<>(warnings);

        return warnings.stream()
                .filter(w -> w.getStationId() == null
                          || w.getStationId().equals(stationId.trim()))
                .toList();
    }

    /**
     * Filtruje ostrzeżenia wg typu (METEO lub HYDRO).
     *
     * @param warnings lista ostrzeżeń
     * @param type     typ do zachowania
     * @return przefiltrowana lista
     */
    public List<Warning> filterByType(List<Warning> warnings, StationType type) {
        if (type == null) return new ArrayList<>(warnings);

        return warnings.stream()
                .filter(w -> type.equals(w.getType()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Parsowanie JSON — wspólny punkt wejścia
    // -------------------------------------------------------------------------

    /**
     * Parsuje odpowiedź API do listy ostrzeżeń. Obsługuje trzy warianty odpowiedzi:
     * tablicę JSON z rekordami, pustą tablicę (brak ostrzeżeń), oraz obiekt
     * {"status":false, ...} który IMGW zwraca czasem zamiast pustej tablicy.
     */
    private List<Warning> parseWarningsArray(String json, StationType type) {
        List<Warning> result = new ArrayList<>();

        try {
            JsonElement root = JsonParser.parseString(json);

            if (root.isJsonObject()) {
                // Odpowiedź typu {"status":false,"message":"No products were found"}
                // — traktujemy jako brak ostrzeżeń, nie jako rekord do sparsowania.
                JsonObject rootObj = root.getAsJsonObject();
                if (rootObj.has("status")) {
                    log.info("API zwróciło status={} — brak ostrzeżeń typu {}",
                            rootObj.get("status"), type);
                    return result;
                }
                log.warn("Nieoczekiwany obiekt JSON (bez 'status') dla ostrzeżeń {} — pomijam", type);
                return result;
            }

            if (!root.isJsonArray()) {
                log.warn("Nieoczekiwany format JSON ostrzeżeń {} — ani tablica, ani obiekt", type);
                return result;
            }

            for (JsonElement element : root.getAsJsonArray()) {
                try {
                    JsonObject obj = element.getAsJsonObject();
                    Warning w = (type == StationType.METEO)
                            ? parseMeteoObject(obj)
                            : parseHydroObject(obj);
                    if (w != null) result.add(w);
                } catch (Exception e) {
                    log.warn("Pominięto nieprawidłowy rekord ostrzeżenia {}: {}", type, e.getMessage());
                }
            }

        } catch (JsonParseException e) {
            log.error("Błąd parsowania ostrzeżeń JSON ({}): {}", type, e.getMessage());
        }

        log.debug("Sparsowano {} ostrzeżeń typu {}", result.size(), type);
        return result;
    }

    // -------------------------------------------------------------------------
    // Parsowanie rekordu METEO
    // -------------------------------------------------------------------------

    /**
     * Mapuje jeden rekord z /warningsmeteo na Warning.
     * Realne pola: id, nazwa_zdarzenia, stopien, prawdopodobienstwo,
     * obowiazuje_od, obowiazuje_do, opublikowano, tresc, komentarz, biuro, teryt[].
     */
    private Warning parseMeteoObject(JsonObject obj) {
        Warning warning = new Warning();

        warning.setId(getString(obj, "id"));
        warning.setStationId(null); // ostrzeżenia meteo są regionalne (teryt), nie per-stacja
        warning.setType(StationType.METEO);
        warning.setLevel(parseLevel(obj, "stopien"));
        warning.setPhenomenon(getString(obj, "nazwa_zdarzenia"));
        warning.setProbability(getInt(obj, "prawdopodobienstwo", -1));
        warning.setIssuedAt(parseDateTime(obj, "opublikowano"));
        warning.setValidUntil(normalizeIndefinite(parseDateTime(obj, "obowiazuje_do")));

        String content = getString(obj, "tresc");
        warning.setMessage(content != null ? content : warning.getPhenomenon());

        if (warning.getId() == null) {
            log.warn("Pominięto rekord meteo bez pola 'id'");
            return null;
        }
        return warning;
    }

    // -------------------------------------------------------------------------
    // Parsowanie rekordu HYDRO
    // -------------------------------------------------------------------------

    /**
     * Mapuje jeden rekord z /warningshydro na Warning.
     * Realne pola: numer (NIE unikalny globalnie), stopień (z polskim ń),
     * prawdopodobienstwo, data_od, data_do, opublikowano, zdarzenie,
     * przebieg, komentarz, biuro, obszary[].
     *
     * API hydro nie udostępnia żadnego pola "id" — identyfikator jest
     * syntetyzowany z numeru i czasu publikacji, co jest wystarczająco
     * unikalne (ten sam "numer" bywa używany ponownie przez różne biura
     * w różnym czasie).
     */
    private Warning parseHydroObject(JsonObject obj) {
        Warning warning = new Warning();

        String numer        = getString(obj, "numer");
        String opublikowano = getString(obj, "opublikowano");

        warning.setId(buildSyntheticHydroId(numer, opublikowano));
        warning.setStationId(null); // ostrzeżenia hydro są regionalne (zlewnie), nie per-stacja
        warning.setType(StationType.HYDRO);
        warning.setLevel(parseLevel(obj, "stopień"));
        warning.setPhenomenon(getString(obj, "zdarzenie"));
        warning.setProbability(getInt(obj, "prawdopodobienstwo", -1));
        warning.setIssuedAt(parseDateTime(obj, "opublikowano"));
        warning.setValidUntil(normalizeIndefinite(parseDateTime(obj, "data_do")));

        String content = getString(obj, "przebieg");
        warning.setMessage(content != null ? content : warning.getPhenomenon());

        return warning;
    }

    /**
     * Buduje syntetyczny identyfikator dla ostrzeżenia hydro, które nie ma
     * natywnego pola "id" w API. Kombinacja numeru biura i znacznika czasu
     * publikacji jest unikalna w praktyce — różne biura wydające ten sam
     * "numer" robią to w różnym czasie.
     */
    private String buildSyntheticHydroId(String numer, String opublikowano) {
        String safeNumer = numer != null ? numer : "0";
        String safeTime  = opublikowano != null
                ? opublikowano.replaceAll("[^0-9]", "")
                : String.valueOf(System.nanoTime());
        return "HYDRO-" + safeNumer + "-" + safeTime;
    }

    // -------------------------------------------------------------------------
    // Pomocnicze parsery
    // -------------------------------------------------------------------------

    /**
     * Parsuje poziom ostrzeżenia z pola numerycznego (1, 2, 3).
     * Wartości poza tym zakresem (np. "-1" dla informacyjnych ostrzeżeń
     * o suszy hydrologicznej) są mapowane na YELLOW jako bezpieczny fallback —
     * IMGW nie przypisuje im koloru w swojej klasyfikacji, ale aplikacja
     * musi wybrać jakiś poziom do wyświetlenia.
     *
     * @param obj      obiekt JSON rekordu
     * @param levelKey nazwa pola poziomu — różna dla meteo ("stopien") i hydro ("stopień")
     */
    private WarningLevel parseLevel(JsonObject obj, String levelKey) {
        String raw = getString(obj, levelKey);
        if (raw == null) {
            log.debug("Brak pola poziomu '{}' — domyślnie YELLOW", levelKey);
            return WarningLevel.YELLOW;
        }

        try {
            int lvl = Integer.parseInt(raw.trim());
            return switch (lvl) {
                case 1  -> WarningLevel.YELLOW;
                case 2  -> WarningLevel.ORANGE;
                case 3  -> WarningLevel.RED;
                default -> {
                    log.debug("Nietypowy poziom ostrzeżenia '{}' = {} — domyślnie YELLOW", levelKey, lvl);
                    yield WarningLevel.YELLOW;
                }
            };
        } catch (NumberFormatException e) {
            log.debug("Nie można sparsować poziomu '{}' = '{}' — domyślnie YELLOW", levelKey, raw);
            return WarningLevel.YELLOW;
        }
    }

    private LocalDateTime parseDateTime(JsonObject obj, String key) {
        String raw = getString(obj, key);
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw.trim(), API_DATETIME_FORMAT);
        } catch (Exception e) {
            log.debug("Błąd parsowania daty ostrzeżenia '{}' = '{}': {}", key, raw, e.getMessage());
            return null;
        }
    }

    /**
     * Zamienia sentinel "bezterminowo" (rok 9999) na null, żeby GUI
     * wyświetlało "bezterminowo" zamiast literalnej daty z roku 9999.
     */
    private LocalDateTime normalizeIndefinite(LocalDateTime dateTime) {
        if (dateTime != null && dateTime.getYear() >= INDEFINITE_YEAR_SENTINEL) {
            return null;
        }
        return dateTime;
    }

    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String val = el.getAsString().trim();
        return val.equals("null") || val.isEmpty() ? null : val;
    }

    private int getInt(JsonObject obj, String key, int defaultValue) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return defaultValue;
        try {
            return Integer.parseInt(el.getAsString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}