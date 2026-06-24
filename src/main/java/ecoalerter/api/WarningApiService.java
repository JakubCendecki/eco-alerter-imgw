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
 * UWAGA — oba endpointy zwracają zupełnie różne nazwy pól dla analogicznych
 * koncepcji, dlatego parsowanie meteo i hydro jest rozdzielone na dwie
 * niezależne metody (parseMeteoObject / parseHydroObject).
 *
 * Mapowanie pól z API IMGW:
 * - meteo: tresc → message, biuro → office, nazwa_zdarzenia → phenomenon
 * - hydro: przebieg → message, biuro → office, zdarzenie → phenomenon
 *
 * Żaden z dwóch endpointów nie udostępnia identyfikatora konkretnej stacji
 * pomiarowej — ostrzeżenia są regionalne, więc Warning.stationId jest zawsze null.
 */
public class WarningApiService {

    private static final Logger log = LogManager.getLogger(WarningApiService.class);

    private static final DateTimeFormatter API_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int INDEFINITE_YEAR_SENTINEL = 9999;

    private final ImgwApiClient apiClient;

    /**
     * @param apiClient klient HTTP do API IMGW
     */
    public WarningApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * Pobiera wszystkie bieżące ostrzeżenia meteorologiczne.
     *
     * @return lista ostrzeżeń; pusta gdy brak alertów lub błąd parsowania
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
     * @return lista ostrzeżeń; pusta gdy brak alertów lub błąd parsowania
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
     * Pobiera ostrzeżenia obu typów (meteo + hydro). Błąd jednego typu nie
     * blokuje pobrania drugiego — wyjątek leci dopiero, gdy obie operacje
     * się nie powiodą.
     */
    public List<Warning> fetchAllWarnings() throws ApiException {
        List<Warning> meteo = Collections.emptyList();
        List<Warning> hydro = Collections.emptyList();
        ApiException lastError = null;

        try { meteo = fetchMeteoWarnings(); }
        catch (ApiException e) {
            log.warn("Nie udało się pobrać ostrzeżeń meteo: {}", e.getMessage());
            lastError = e;
        }

        try { hydro = fetchHydroWarnings(); }
        catch (ApiException e) {
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

    /** Filtruje listę ostrzeżeń — zwraca tylko te o poziomie {@code >= minLevel}. */
    public List<Warning> filterByMinLevel(List<Warning> warnings, WarningLevel minLevel) {
        if (warnings == null || warnings.isEmpty()) return Collections.emptyList();
        if (minLevel == null) return new ArrayList<>(warnings);
        return warnings.stream()
                .filter(w -> w.getLevel() != null && w.getLevel().ordinal() >= minLevel.ordinal())
                .toList();
    }

    /**
     * Filtruje ostrzeżenia dotyczące konkretnej stacji. Ponieważ API IMGW
     * nie przypisuje ostrzeżeń do stacji pomiarowych (są regionalne),
     * w praktyce zwraca wszystkie ostrzeżenia.
     */
    public List<Warning> filterByStation(List<Warning> warnings, String stationId) {
        if (stationId == null || stationId.isBlank()) return new ArrayList<>(warnings);
        return warnings.stream()
                .filter(w -> w.getStationId() == null || w.getStationId().equals(stationId.trim()))
                .toList();
    }

    /** Filtruje ostrzeżenia wg typu (METEO lub HYDRO). */
    public List<Warning> filterByType(List<Warning> warnings, StationType type) {
        if (type == null) return new ArrayList<>(warnings);
        return warnings.stream()
                .filter(w -> type.equals(w.getType()))
                .toList();
    }

    /**
     * Parsuje odpowiedź API do listy ostrzeżeń. Obsługuje trzy warianty:
     * tablicę JSON z rekordami, pustą tablicę (brak ostrzeżeń), oraz obiekt
     * {@code {"status":false, ...}} który IMGW zwraca czasem zamiast pustej tablicy.
     */
    private List<Warning> parseWarningsArray(String json, StationType type) {
        List<Warning> result = new ArrayList<>();

        try {
            JsonElement root = JsonParser.parseString(json);

            if (root.isJsonObject()) {
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

    /**
     * Mapuje jeden rekord z {@code /warningsmeteo} na {@link Warning}.
     * Pola: id, nazwa_zdarzenia, stopien, prawdopodobienstwo, obowiazuje_od,
     * obowiazuje_do, opublikowano, tresc, komentarz, biuro, teryt[].
     */
    private Warning parseMeteoObject(JsonObject obj) {
        Warning warning = new Warning();

        warning.setId(getString(obj, "id"));
        warning.setStationId(null);
        warning.setType(StationType.METEO);
        warning.setLevel(parseLevel(obj, "stopien"));
        warning.setPhenomenon(getString(obj, "nazwa_zdarzenia"));
        warning.setProbability(getInt(obj, "prawdopodobienstwo", -1));
        warning.setIssuedAt(parseDateTime(obj, "opublikowano"));
        warning.setValidUntil(normalizeIndefinite(parseDateTime(obj, "obowiazuje_do")));

        // Treść komunikatu + biuro wydające (NEW: oba pola pokazywane w dialogu szczegółów)
        String content = getString(obj, "tresc");
        warning.setMessage(content != null ? content : warning.getPhenomenon());
        warning.setOffice(getString(obj, "biuro"));

        if (warning.getId() == null) {
            log.warn("Pominięto rekord meteo bez pola 'id'");
            return null;
        }
        return warning;
    }

    /**
     * Mapuje jeden rekord z {@code /warningshydro} na {@link Warning}.
     * Pola: numer (NIE unikalny globalnie), stopień (z polskim ń),
     * prawdopodobienstwo, data_od, data_do, opublikowano, zdarzenie,
     * przebieg, komentarz, biuro, obszary[].
     *
     * API hydro nie udostępnia pola "id" — identyfikator jest syntetyzowany
     * z numeru i czasu publikacji.
     */
    private Warning parseHydroObject(JsonObject obj) {
        Warning warning = new Warning();

        String numer = getString(obj, "numer");
        String opublikowano = getString(obj, "opublikowano");
        warning.setId(buildSyntheticHydroId(numer, opublikowano));

        warning.setStationId(null);
        warning.setType(StationType.HYDRO);
        warning.setLevel(parseLevel(obj, "stopień"));
        warning.setPhenomenon(getString(obj, "zdarzenie"));
        warning.setProbability(getInt(obj, "prawdopodobienstwo", -1));
        warning.setIssuedAt(parseDateTime(obj, "opublikowano"));
        warning.setValidUntil(normalizeIndefinite(parseDateTime(obj, "data_do")));

        // Treść komunikatu (w hydro to "przebieg") + biuro wydające
        String content = getString(obj, "przebieg");
        warning.setMessage(content != null ? content : warning.getPhenomenon());
        warning.setOffice(getString(obj, "biuro"));

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
        String safeTime = opublikowano != null
                ? opublikowano.replaceAll("[^0-9]", "")
                : String.valueOf(System.nanoTime());
        return "HYDRO-" + safeNumer + "-" + safeTime;
    }

    /**
     * Parsuje poziom ostrzeżenia z pola numerycznego (1, 2, 3). Wartości
     * spoza zakresu są mapowane na YELLOW jako bezpieczny fallback.
     *
     * @param obj      obiekt JSON rekordu
     * @param levelKey nazwa pola — różna dla meteo ("stopien") i hydro ("stopień")
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
                case 1 -> WarningLevel.YELLOW;
                case 2 -> WarningLevel.ORANGE;
                case 3 -> WarningLevel.RED;
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

    /** Parsuje pole daty z obiektu JSON. Zwraca null gdy pole jest puste lub nieparsowalne. */
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

    /** Bezpiecznie czyta string z JSON; zwraca null dla pustych, "null" i braków. */
    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String val = el.getAsString().trim();
        return val.equals("null") || val.isEmpty() ? null : val;
    }

    /** Bezpiecznie czyta int z JSON; zwraca defaultValue dla braków lub błędów. */
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