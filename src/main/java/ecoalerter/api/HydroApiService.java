package ecoalerter.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import ecoalerter.model.HydroData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Serwis pobierający i parsujący dane hydrologiczne z API IMGW-PIB.
 *
 * Komunikuje się z endpointami /hydro i /hydro/id/{id}.
 *
 * Rzeczywista odpowiedź API (przykład):
 *   id_stacji, stacja, rzeka, wojewodztwo, lon, lat,
 *   stan_wody, stan_wody_data_pomiaru,
 *   temperatura_wody, temperatura_wody_data_pomiaru,
 *   przeplyw, przeplyw_data,
 *   zjawisko_lodowe, zjawisko_lodowe_data_pomiaru,
 *   zjawisko_zarastania, zjawisko_zarastania_data_pomiaru
 *
 * Uwaga — każdy pomiar ma WŁASNY, niezależny znacznik czasu; mogą się
 * różnić o miesiące (np. stan wody mierzony codziennie, a przepływ raz
 * na kilka miesięcy). Model HydroData ma jedno pole timestamp dla całego
 * rekordu, więc jako wartość kanoniczną przyjmujemy znacznik czasu stanu
 * wody (najważniejszy, najczęściej aktualizowany parametr hydrologiczny),
 * z awaryjnym przejściem na inne dostępne znaczniki gdy stan wody jest null.
 */
public class HydroApiService {

    private static final Logger log = LogManager.getLogger(HydroApiService.class);

    /** Format daty używany przez API — zawiera sekundy. */
    private static final DateTimeFormatter API_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ImgwApiClient apiClient;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public HydroApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Pobiera dane hydrologiczne ze wszystkich aktywnych stacji IMGW.
     *
     * @return lista danych hydro; pusta lista przy błędzie parsowania
     * @throws ApiException gdy żądanie HTTP nie powiedzie się
     */
    public List<HydroData> fetchAllStations() throws ApiException {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.HYDRO_ALL);
        log.info("Pobieranie danych hydro — wszystkie stacje: {}", url);

        String json = apiClient.get(url);
        return parseArray(json);
    }

    /**
     * Pobiera dane hydrologiczne dla konkretnej stacji wg jej ID IMGW.
     *
     * @param stationId identyfikator stacji (np. "150180180")
     * @return Optional z danymi lub empty() gdy stacja nie istnieje
     * @throws ApiException gdy żądanie HTTP nie powiedzie się (poza 404)
     */
    public Optional<HydroData> fetchById(String stationId) throws ApiException {
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("stationId nie może być pusty");
        }

        String url = ApiEndpoints.fullUrl(ApiEndpoints.HYDRO_BY_ID, stationId.trim());
        log.info("Pobieranie danych hydro — stacja {}: {}", stationId, url);

        try {
            String json = apiClient.get(url);
            return Optional.ofNullable(parseSingle(json));
        } catch (ApiException e) {
            if (e.isNotFound()) {
                log.warn("Stacja hydro nie znaleziona: {}", stationId);
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Pobiera dane dla listy stacji hydrologicznych (sekwencyjnie).
     * Stacje, które zwrócą 404 lub błąd parsowania, są pomijane.
     *
     * @param stationIds lista identyfikatorów stacji
     * @return lista zebranych danych
     * @throws ApiException gdy wystąpi krytyczny błąd sieci
     */
    public List<HydroData> fetchByIds(List<String> stationIds) throws ApiException {
        if (stationIds == null || stationIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<HydroData> results = new ArrayList<>(stationIds.size());

        for (String id : stationIds) {
            try {
                fetchById(id).ifPresent(results::add);
            } catch (ApiException e) {
                if (e.isNetworkError()) {
                    throw e;
                }
                log.warn("Pominięto stację hydro {} z powodu błędu: {}", id, e.getMessage());
            }
        }

        log.info("Pobrano dane hydro dla {}/{} stacji", results.size(), stationIds.size());
        return results;
    }

    /**
     * Filtruje listę danych hydro wg nazwy rzeki (ignoruje wielkość liter).
     *
     * @param allData   lista danych do filtrowania
     * @param riverName nazwa rzeki (np. "Wisła")
     * @return przefiltrowana lista
     */
    public List<HydroData> filterByRiver(List<HydroData> allData, String riverName) {
        if (riverName == null || riverName.isBlank()) {
            return allData;
        }
        String search = riverName.trim().toLowerCase();
        return allData.stream()
                .filter(d -> d.getRiverName() != null
                          && d.getRiverName().toLowerCase().contains(search))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Parsowanie JSON
    // -------------------------------------------------------------------------

    private List<HydroData> parseArray(String json) {
        List<HydroData> result = new ArrayList<>();

        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                try {
                    HydroData data = parseObject(element.getAsJsonObject());
                    if (data != null) {
                        result.add(data);
                    }
                } catch (Exception e) {
                    log.warn("Pominięto nieprawidłowy rekord hydro: {}", e.getMessage());
                }
            }
        } catch (JsonParseException e) {
            log.error("Błąd parsowania odpowiedzi hydro (array): {}", e.getMessage());
        }

        log.debug("Sparsowano {} rekordów hydro", result.size());
        return result;
    }

    private HydroData parseSingle(String json) {
        try {
            // API może zwrócić tablicę z jednym elementem lub pojedynczy obiekt
            json = json.trim();
            if (json.startsWith("[")) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                return parseObject(arr.get(0).getAsJsonObject());
            }
            return parseObject(JsonParser.parseString(json).getAsJsonObject());
        } catch (JsonParseException e) {
            log.error("Błąd parsowania odpowiedzi hydro (single): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Mapuje jeden rekord JSON na HydroData, używając rzeczywistych nazw pól
     * API IMGW (nie skrótów zgadywanych "na oko").
     */
    private HydroData parseObject(JsonObject obj) {
        String stationId   = getString(obj, "id_stacji");
        String stationName = getString(obj, "stacja");
        String riverName   = getString(obj, "rzeka");
        String voivodeship = getString(obj, "wojewodztwo");

        if (stationId == null || stationId.isBlank()) {
            log.warn("Pominięto rekord hydro bez id_stacji");
            return null;
        }

        LocalDateTime waterLevelTime = parseDateTime(obj, "stan_wody_data_pomiaru");
        LocalDateTime waterTempTime  = parseDateTime(obj, "temperatura_wody_data_pomiaru");
        LocalDateTime flowTime       = parseDateTime(obj, "przeplyw_data");
        LocalDateTime iceTime        = parseDateTime(obj, "zjawisko_lodowe_data_pomiaru");
        LocalDateTime overgrowthTime = parseDateTime(obj, "zjawisko_zarastania_data_pomiaru");

        HydroData data = new HydroData();
        data.setStationId(stationId);
        data.setStationName(stationName != null ? stationName : "");
        data.setRiverName(riverName != null ? riverName : "");
        data.setVoivodeship(voivodeship != null ? voivodeship : "");
        data.setWaterLevel(getDouble(obj, "stan_wody"));
        data.setWaterTemperature(getDouble(obj, "temperatura_wody"));
        data.setFlow(getDouble(obj, "przeplyw"));
        data.setIcePhenomenon(getInt(obj, "zjawisko_lodowe", 0));
        data.setOvergrowthPhenomenon(getInt(obj, "zjawisko_zarastania", 0));

        // Znacznik czasu całego rekordu — preferujemy czas pomiaru stanu wody
        // (najważniejszy i najczęściej aktualizowany parametr), z awaryjnym
        // przejściem na inne dostępne znaczniki czasu tego samego rekordu.
        data.setTimestamp(firstNonNull(
                waterLevelTime, waterTempTime, flowTime, iceTime, overgrowthTime));

        return data;
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze do bezpiecznego odczytu pól JSON
    // -------------------------------------------------------------------------

    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String val = el.getAsString().trim();
        return val.equals("null") || val.isEmpty() ? null : val;
    }

    private Double getDouble(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String raw = el.getAsString().trim();
        if (raw.isEmpty() || raw.equalsIgnoreCase("null")) return null;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            log.debug("Nie można sparsować pola '{}' = '{}' jako Double", key, raw);
            return null;
        }
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

    private LocalDateTime parseDateTime(JsonObject obj, String key) {
        String raw = getString(obj, key);
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw.trim(), API_DATETIME_FORMAT);
        } catch (Exception e) {
            log.debug("Błąd parsowania daty hydro '{}' = '{}': {}", key, raw, e.getMessage());
            return null;
        }
    }

    /**
     * Zwraca pierwszy niepusty znacznik czasu z podanych kandydatów,
     * albo aktualny czas gdy wszystkie są null (rekord bez żadnego
     * poprawnego znacznika czasu — nie powinno się zdarzyć w praktyce).
     */
    private LocalDateTime firstNonNull(LocalDateTime... candidates) {
        for (LocalDateTime candidate : candidates) {
            if (candidate != null) return candidate;
        }
        return LocalDateTime.now();
    }
}