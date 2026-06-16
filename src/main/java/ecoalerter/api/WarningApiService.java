package ecoalerter.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import ecoalerter.model.StationType;
import ecoalerter.model.Warning;
import ecoalerter.model.WarningLevel;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Serwis pobierający, parsujący i filtrujący ostrzeżenia pogodowe
 * oraz hydrologiczne z API IMGW-PIB.
 *
 * Korzysta z endpointów:
 *  {@link ApiEndpoints#WARNINGS_METEO} — alerty meteorologiczne
 *  {@link ApiEndpoints#WARNINGS_HYDRO} — alerty hydrologiczne
 *
 * Poziomy alertów IMGW:
 *   1 / "YELLOW"  — ostrzeżenie 1. stopnia (żółte)
 *   2 / "ORANGE"  — ostrzeżenie 2. stopnia (pomarańczowe)
 *   3 / "RED"     — ostrzeżenie 3. stopnia (czerwone)
*/
public class WarningApiService {
    private static final Logger log = LogManager.getLogger(WarningApiService.class);

    private static final DateTimeFormatter API_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ImgwApiClient apiClient;

    public WarningApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
    }

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
            return parseWarnings(json, StationType.METEO);
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
            return parseWarnings(json, StationType.HYDRO);
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
                // Obydwa żądania zakończyły się błędem — rzucamy wyjątek
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
     *
     * @param warnings lista ostrzeżeń
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

    /** Filtruje ostrzeżenia wg typu (METEO lub HYDRO). */
    public List<Warning> filterByType(List<Warning> warnings, StationType type) {
        if (type == null) return new ArrayList<>(warnings);

        return warnings.stream()
                .filter(w -> type.equals(w.getType()))
                .toList();
    }

    private List<Warning> parseWarnings(String json, StationType defaultType) {
        List<Warning> result = new ArrayList<>();

        try {
            JsonElement root = JsonParser.parseString(json);

            // API może zwrócić tablicę lub obiekt z kluczem "warnings"
            JsonArray array;
            if (root.isJsonArray()) {
                array = root.getAsJsonArray();
            } else if (root.isJsonObject()) {
                JsonObject rootObj = root.getAsJsonObject();
                if (rootObj.has("warnings")) {
                    array = rootObj.getAsJsonArray("warnings");
                } else {
                    // Pojedynczy obiekt
                    Warning w = parseWarningObject(rootObj, defaultType);
                    if (w != null) result.add(w);
                    return result;
                }
            } else {
                log.warn("Nieoczekiwany format JSON ostrzeżeń");
                return result;
            }

            for (JsonElement element : array) {
                try {
                    Warning w = parseWarningObject(element.getAsJsonObject(), defaultType);
                    if (w != null) result.add(w);
                } catch (Exception e) {
                    log.warn("Pominięto nieprawidłowy rekord ostrzeżenia: {}", e.getMessage());
                }
            }

        } catch (JsonParseException e) {
            log.error("Błąd parsowania ostrzeżeń JSON: {}", e.getMessage());
        }

        log.debug("Sparsowano {} ostrzeżeń typu {}", result.size(), defaultType);
        return result;
    }
    
    /**
     * Mapuje {@link JsonObject} na {@link Warning}.
     * Pola liczbowe mogą być w API jako String lub Number — metoda obsługuje oba przypadki.
    */
    private Warning parseWarningObject(JsonObject obj, StationType defaultType) {
        Warning warning = new Warning();

        warning.setId(getString(obj, "id"));
        warning.setStationId(getString(obj, "id_stacji"));
        warning.setLevel(parseLevel(obj));
        warning.setType(parseType(obj, defaultType));
        warning.setPhenomenon(getString(obj, "phenomenon"));
        warning.setProbability(getInt(obj, "probability", -1));
        warning.setIssuedAt(parseDateTime(obj, "dateFrom"));
        warning.setValidUntil(parseDateTime(obj, "dateTo"));

        // Budowanie treści wiadomości
        String phenomenon = warning.getPhenomenon();
        int probability   = warning.getProbability();
        String msg = phenomenon != null ? phenomenon : "Ostrzeżenie " + warning.getType();
        if (probability > 0) msg += " (prawdopodobieństwo: " + probability + "%)";
        warning.setMessage(msg);

        return warning;
    }

    /** Parsuje poziom ostrzeżenia — akceptuje zarówno liczby (1,2,3) jak i nazwy ("YELLOW","ORANGE","RED"). */
    private WarningLevel parseLevel(JsonObject obj) {
        // Próba po nazwie tekstowej
        String levelName = getString(obj, "levelName");
        if (levelName != null) {
            try {
                return WarningLevel.valueOf(levelName.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        // Próba po numerze
        JsonElement levelEl = obj.get("level");
        if (levelEl != null && !levelEl.isJsonNull()) {
            try {
                int lvl = Integer.parseInt(levelEl.getAsString().trim());
                return switch (lvl) {
                    case 1  -> WarningLevel.YELLOW;
                    case 2  -> WarningLevel.ORANGE;
                    case 3  -> WarningLevel.RED;
                    default -> WarningLevel.YELLOW;
                };
            } catch (NumberFormatException ignored) {}
        }

        log.debug("Nieznany poziom ostrzeżenia — domyślnie YELLOW");
        return WarningLevel.YELLOW;
    }

    /** Parsuje typ ostrzeżenia (METEO / HYDRO) lub zwraca typ domyślny. */
    private StationType parseType(JsonObject obj, StationType defaultType) {
        String raw = getString(obj, "warningType");
        if (raw == null) return defaultType;
        try {
            return StationType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultType;
        }
    }

    /** Łączy datę i godzinę z API w {@link LocalDateTime}. */
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
    
    /** Odczytuje String lub null jeśli pole nie istnieje lub jest null. */
    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String val = el.getAsString().trim();
        return val.equals("null") || val.isEmpty() ? null : val;
    }
    
    /** Odczytuje Int lub jeśli pole nie istnieje, ustawia wartość na defaultValue */
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