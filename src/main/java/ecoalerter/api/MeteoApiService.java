package ecoalerter.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import ecoalerter.model.MeteoData;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Serwis pobierający i parsujący dane meteorologiczne z API IMGW-PIB.
 *
 * Komunikuje się z endpointami {@code /synop} i {@code /synop/id/{id}}.
 * Odpowiedzi API są deserializowane do obiektów {@link MeteoData}.
*/
public class MeteoApiService {

    private static final Logger log = LogManager.getLogger(MeteoApiService.class);

    // Format daty i godziny z API IMGW
    private static final DateTimeFormatter API_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

    private final ImgwApiClient apiClient;

    public MeteoApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
        new GsonBuilder().create();
    }

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
     * @param stationId identyfikator stacji (np. {@code "12200"})
     * @return {@link Optional} z danymi lub {@code empty()} gdy stacja nie istnieje
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
                // krytyczny błąd sieci — przerywamy całe pobieranie
                if (e.isNetworkError()) {
                    throw e;
                }
                // błąd HTTP dla konkretnej stacji — logujemy i kontynuujemy
                log.warn("Pominięto stację meteo {} z powodu błędu: {}", id, e.getMessage());
            }
        }

        log.info("Pobrano dane meteo dla {}/{} stacji", results.size(), stationIds.size());
        return results;
    }

    /** Parsuje tablicę JSON z wieloma stacjami do listy {@link MeteoData}. */
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

    /** Parsuje pojedynczy obiekt JSON (jeden stacja) do {@link MeteoData}. */
    private MeteoData parseSingle(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            return parseObject(obj);
        } catch (JsonParseException e) {
            log.error("Błąd parsowania odpowiedzi meteo (single): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Mapuje {@link JsonObject} na {@link MeteoData}.
     * Pola liczbowe mogą być w API jako String lub Number — metoda obsługuje oba przypadki.
    */
    private MeteoData parseObject(JsonObject obj) {
        String stationId   = getString(obj, "id_stacji");
        String stationName = getString(obj, "stacja");
        String date        = getString(obj, "data_pomiaru");
        String hour        = getString(obj, "godzina_pomiaru");

        if (stationId == null || stationId.isBlank()) {
            log.warn("Pominięto rekord meteo bez id_stacji");
            return null;
        }

        LocalDateTime timestamp = parseTimestamp(date, hour);

        MeteoData data = new MeteoData();
        data.setStationId(stationId);
        data.setStationName(stationName != null ? stationName : "");
        data.setTimestamp(timestamp);
        data.setTemperature(getDouble(obj, "temperatura"));
        data.setWindSpeed(getDouble(obj, "predkosc_wiatru"));
        data.setPrecipitation(getDouble(obj, "suma_opadu"));
        data.setPressure(getDouble(obj, "cisnienie"));

        return data;
    }

    /** Odczytuje String lub null jeśli pole nie istnieje lub jest null. */
    private String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        String val = el.getAsString().trim();
        return val.equals("null") ? null : val;
    }

    /** Odczytuje Double lub null jeśli pole jest puste, "null" lub nie istnieje. */
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

    /** Łączy datę i godzinę z API w {@link LocalDateTime}. */
    private LocalDateTime parseTimestamp(String date, String hour) {
        if (date == null || hour == null) return LocalDateTime.now();
        try {
            // Normalizacja: godzina może być "6" zamiast "06"
            String paddedHour = hour.trim().length() == 1 ? "0" + hour.trim() : hour.trim();
            return LocalDateTime.parse(date.trim() + " " + paddedHour, API_DATE_FORMAT);
        } catch (Exception e) {
            log.debug("Błąd parsowania daty meteo [{} {}]: {}", date, hour, e.getMessage());
            return LocalDateTime.now();
        }
    }
}