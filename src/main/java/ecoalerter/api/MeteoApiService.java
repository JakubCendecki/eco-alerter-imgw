package ecoalerter.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import ecoalerter.model.MeteoData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Serwis pobierający i parsujący dane meteorologiczne z API IMGW-PIB.
 *
 * Komunikuje się z endpointami /meteo i /meteo/id/{id} — sieć automatycznych
 * stacji pogodowych (AWS), NIE sieć stacji synoptycznych (/synop).
 *
 * Rzeczywista odpowiedź API (przykład):
 *   kod_stacji, nazwa_stacji, lon, lat,
 *   temperatura_gruntu, temperatura_gruntu_data,
 *   temperatura_powietrza, temperatura_powietrza_data,
 *   wiatr_kierunek, wiatr_kierunek_data,
 *   wiatr_srednia_predkosc, wiatr_srednia_predkosc_data,
 *   wiatr_predkosc_maksymalna, wiatr_predkosc_maksymalna_data,
 *   wilgotnosc_wzgledna, wilgotnosc_wzgledna_data,
 *   wiatr_poryw_10min, wiatr_poryw_10min_data,
 *   opad_10min, opad_10min_data
 *
 * UWAGA — ta sieć stacji nie mierzy ciśnienia atmosferycznego, dlatego
 * model MeteoData nie zawiera takiego pola.
 *
 * Każdy pomiar ma własny, niezależny znacznik czasu (podobnie jak w danych
 * hydrologicznych). Jako czas całego rekordu przyjmujemy NAJNOWSZY ze znaczników
 * dostępnych pól — nie pierwszy z nich — żeby uniknąć sytuacji, w której jedno
 * pole (np. temperatura) ma wciąż stary znacznik, a inne (np. wiatr) już nowy,
 * co przy użyciu "pierwszego pola" jako czasu rekordu powodowałoby błędne
 * odrzucenie nowych danych przez ograniczenie UNIQUE(station_id, timestamp).
 */
public class MeteoApiService {

    private static final Logger log = LogManager.getLogger(MeteoApiService.class);

    /** Format daty używany przez API — zawiera sekundy. */
    private static final DateTimeFormatter API_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ImgwApiClient apiClient;

    // -------------------------------------------------------------------------
    // Konstruktor
    // -------------------------------------------------------------------------

    public MeteoApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
    }

    // -------------------------------------------------------------------------
    // Publiczny interfejs
    // -------------------------------------------------------------------------

    /**
     * Pobiera dane meteorologiczne ze wszystkich aktywnych stacji IMGW.
     *
     * @return lista danych meteo; pusta lista przy błędzie parsowania
     * @throws ApiException gdy żądanie HTTP nie powiedzie się
     */
    public List<MeteoData> fetchAllStations() throws ApiException {
        String url = ApiEndpoints.fullUrl(ApiEndpoints.METEO_ALL);
        log.info("Pobieranie danych meteo — wszystkie stacje: {}", url);

        String json = apiClient.get(url);
        return parseArray(json);
    }

    /**
     * Pobiera dane meteorologiczne dla konkretnej stacji wg jej ID IMGW.
     *
     * @param stationId identyfikator stacji (np. "352230399")
     * @return Optional z danymi lub empty() gdy stacja nie istnieje
     * @throws ApiException gdy żądanie HTTP nie powiedzie się (poza 404)
     */
    public Optional<MeteoData> fetchById(String stationId) throws ApiException {
        if (stationId == null || stationId.isBlank()) {
            throw new IllegalArgumentException("stationId nie może być pusty");
        }

        String url = ApiEndpoints.fullUrl(ApiEndpoints.METEO_BY_ID, stationId.trim());
        log.info("Pobieranie danych meteo — stacja {}: {}", stationId, url);

        try {
            String json = apiClient.get(url);
            return Optional.ofNullable(parseSingle(json));
        } catch (ApiException e) {
            if (e.isNotFound()) {
                log.warn("Stacja meteo nie znaleziona: {}", stationId);
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Pobiera dane dla listy stacji w jednym wywołaniu (sekwencyjnie).
     * Stacje, które zwrócą 404 lub błąd parsowania, są pomijane.
     *
     * @param stationIds lista identyfikatorów stacji
     * @return lista zebranych danych (może być krótsza niż stationIds)
     * @throws ApiException gdy wystąpi krytyczny błąd sieci
     */
    public List<MeteoData> fetchByIds(List<String> stationIds) throws ApiException {
        if (stationIds == null || stationIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<MeteoData> results = new ArrayList<>(stationIds.size());

        for (String id : stationIds) {
            try {
                fetchById(id).ifPresent(results::add);
            } catch (ApiException e) {
                if (e.isNetworkError()) {
                    throw e;
                }
                log.warn("Pominięto stację meteo {} z powodu błędu: {}", id, e.getMessage());
            }
        }

        log.info("Pobrano dane meteo dla {}/{} stacji", results.size(), stationIds.size());
        return results;
    }

    // -------------------------------------------------------------------------
    // Parsowanie JSON
    // -------------------------------------------------------------------------

    private List<MeteoData> parseArray(String json) {
        List<MeteoData> result = new ArrayList<>();

        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                try {
                    MeteoData data = parseObject(element.getAsJsonObject());
                    if (data != null) {
                        result.add(data);
                    }
                } catch (Exception e) {
                    log.warn("Pominięto nieprawidłowy rekord meteo: {}", e.getMessage());
                }
            }
        } catch (JsonParseException e) {
            log.error("Błąd parsowania odpowiedzi meteo (array): {}", e.getMessage());
        }

        log.debug("Sparsowano {} rekordów meteo", result.size());
        return result;
    }

    private MeteoData parseSingle(String json) {
        try {
            json = json.trim();
            if (json.startsWith("[")) {
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                return parseObject(arr.get(0).getAsJsonObject());
            }
            return parseObject(JsonParser.parseString(json).getAsJsonObject());
        } catch (JsonParseException e) {
            log.error("Błąd parsowania odpowiedzi meteo (single): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Mapuje jeden rekord JSON na MeteoData, używając rzeczywistych nazw pól
     * sieci AWS IMGW.
     */
    private MeteoData parseObject(JsonObject obj) {
        String stationId   = getString(obj, "kod_stacji");
        String stationName = getString(obj, "nazwa_stacji");

        if (stationId == null || stationId.isBlank()) {
            log.warn("Pominięto rekord meteo bez kod_stacji");
            return null;
        }

        LocalDateTime tempTime = parseDateTime(obj, "temperatura_powietrza_data");
        LocalDateTime windTime = parseDateTime(obj, "wiatr_srednia_predkosc_data");
        LocalDateTime precTime = parseDateTime(obj, "opad_10min_data");

        MeteoData data = new MeteoData();
        data.setStationId(stationId);
        data.setStationName(stationName != null ? stationName : "");
        data.setTemperature(getDouble(obj, "temperatura_powietrza"));
        data.setWindSpeed(getDouble(obj, "wiatr_srednia_predkosc"));
        data.setPrecipitation(getDouble(obj, "opad_10min"));

        // Znacznik czasu całego rekordu — bierzemy NAJNOWSZY z dostępnych
        // znaczników (nie pierwszy), żeby uniknąć błędnego odrzucenia przez
        // UNIQUE(station_id, timestamp): gdyby temperatura miała wciąż stary,
        // niezmieniony znacznik, a wiatr już nowy, użycie "pierwszego" pola
        // (temperatury) jako kanonicznego czasu rekordu sprawiłoby, że kolejne
        // realnie nowe pomiary wiatru byłyby traktowane jako duplikat i pomijane.
        data.setTimestamp(latestOf(tempTime, windTime, precTime));

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

    private LocalDateTime parseDateTime(JsonObject obj, String key) {
        String raw = getString(obj, key);
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw.trim(), API_DATETIME_FORMAT);
        } catch (Exception e) {
            log.debug("Błąd parsowania daty meteo '{}' = '{}': {}", key, raw, e.getMessage());
            return null;
        }
    }

    /**
     * Zwraca najnowszy (maksymalny) znacznik czasu z podanych kandydatów,
     * albo aktualny czas gdy wszystkie są null. Użycie maksimum, nie pierwszej
     * niepustej wartości, gwarantuje że jeśli JAKIEKOLWIEK pole rekordu dostało
     * nowszy pomiar, cały rekord dostaje nowszy znacznik czasu — co zapobiega
     * cichemu odrzuceniu nowych danych przez UNIQUE(station_id, timestamp).
     */
    private LocalDateTime latestOf(LocalDateTime... candidates) {
        LocalDateTime latest = null;
        for (LocalDateTime candidate : candidates) {
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest != null ? latest : LocalDateTime.now();
    }
}