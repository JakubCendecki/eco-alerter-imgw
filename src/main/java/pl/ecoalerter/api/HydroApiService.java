package pl.ecoalerter.api;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import pl.ecoalerter.model.HydroData;
import pl.ecoalerter.model.MeteoData;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Serwis pobierający i parsujący dane hydrologiczne z API IMGW-PIB.
 *
 * Komunikuje się z endpointami {@code /hydro} i {@code /hydro/id/{id}}.
 * Odpowiedzi API są deserializowane do obiektów {@link HydroData}.
*/
public class HydroApiService {

    private static final Logger log = LogManager.getLogger(HydroApiService.class);

    private static final DateTimeFormatter API_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH");

    private final ImgwApiClient apiClient;

    public HydroApiService(ImgwApiClient apiClient) {
        this.apiClient = apiClient;
    }

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
     * @param stationId identyfikator stacji (np. {@code "150180180"})
     * @return {@link Optional} z danymi lub {@code empty()} gdy stacja nie istnieje
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
     * @param allData  lista danych do filtrowania
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
    
    /** Parsuje tablicę JSON z wieloma stacjami do listy */
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

    /** Parsuje pojedynczy obiekt JSON (jeden stacja) */
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
     * Mapuje {@link JsonObject} na {@link MeteoData}.
     * Pola liczbowe mogą być w API jako String lub Number — metoda obsługuje oba przypadki.
    */
    private HydroData parseObject(JsonObject obj) {
        String stationId   = getString(obj, "id_stacji");
        String stationName = getString(obj, "stacja");
        String riverName   = getString(obj, "rzeka");
        String voivodeship = getString(obj, "województwo");
        String date        = getString(obj, "data_pomiaru");
        String hour        = getString(obj, "godzina_pomiaru");

        if (stationId == null || stationId.isBlank()) {
            log.warn("Pominięto rekord hydro bez id_stacji");
            return null;
        }

        LocalDateTime timestamp = parseTimestamp(date, hour);

        HydroData data = new HydroData();
        data.setStationId(stationId);
        data.setStationName(stationName != null ? stationName : "");
        data.setRiverName(riverName != null ? riverName : "");
        data.setVoivodeship(voivodeship != null ? voivodeship : "");
        data.setTimestamp(timestamp);
        data.setWaterLevel(getDouble(obj, "stan_wody"));
        data.setWaterTemperature(getDouble(obj, "temperatura_wody"));
        data.setIcePhenomenon(getInt(obj, "zjawisko_lodowe", 0));
        data.setOvergrowthPhenomenon(getInt(obj, "zjawisko_zarastania", 0));
        data.setFlow(getDouble(obj, "przelyw"));

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

    /** Łączy datę i godzinę z API w {@link LocalDateTime}. */
    private LocalDateTime parseTimestamp(String date, String hour) {
        if (date == null || hour == null) return LocalDateTime.now();
        try {
            String paddedHour = hour.trim().length() == 1 ? "0" + hour.trim() : hour.trim();
            return LocalDateTime.parse(date.trim() + " " + paddedHour, API_DATE_FORMAT);
        } catch (Exception e) {
            log.debug("Błąd parsowania daty hydro [{} {}]: {}", date, hour, e.getMessage());
            return LocalDateTime.now();
        }
    }
}