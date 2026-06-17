package ecoalerter.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Narzędziowy wrapper biblioteki Gson do serializacji i deserializacji JSON.
 *
 * Udostępnia dwie instancje Gson:
 *   compact — do zapisu do pliku i transmisji (bez wcięć)
 *   pretty  — do debugowania i czytelnego podglądu
 *
 * Klasa obsługuje {@link LocalDateTime} przez dedykowaną strategię serializacji.
 * Wszystkie metody są null-safe i nie rzucają wyjątków runtime do wywołującego —
 * błędy są logowane i zwracane jako {@link Optional#empty()} lub puste kolekcje.
*/
public final class JsonParser {

    private static final Logger log = LogManager.getLogger(JsonParser.class);

    /** Instancja do kompaktowego zapisu (produkcja, pliki). */
    private static final Gson COMPACT = buildGson(false);

    /** Instancja do czytelnego wydruku (debug, podgląd). */
    private static final Gson PRETTY  = buildGson(true);

    /**
     * Serializuje obiekt do kompaktowego JSON (bez wcięć).
     *
     * @param object obiekt do serializacji; {@code null} → {@code "null"}
     * @return string JSON
    */
    public static String toJson(Object object) {
        return COMPACT.toJson(object);
    }

    /**
     * Serializuje obiekt do sformatowanego (czytelnego) JSON z wcięciami.
     * Używane do plików i debugowania.
     *
     * @param object obiekt do serializacji; {@code null} → {@code "null"}
     * @return sformatowany string JSON
    */
    public static String toPrettyJson(Object object) {
        return PRETTY.toJson(object);
    }

    /**
     * Serializuje listę obiektów do JSON.
     *
     * @param list   lista do serializacji; {@code null} → {@code "[]"}
     * @param pretty czy użyć wcięć
     * @return string JSON reprezentujący tablicę
    */
    public static String listToJson(List<?> list, boolean pretty) {
        if (list == null) return "[]";
        return pretty ? PRETTY.toJson(list) : COMPACT.toJson(list);
    }

    /**
     * Deserializuje JSON do obiektu podanej klasy.
     *
     * @param json  string JSON; {@code null}/pusty zwraca {@code empty()}
     * @param clazz docelowa klasa
     * @param <T>   typ wyniku
     * @return {@link Optional} z wynikiem lub {@code empty()} przy błędzie/null
    */
    public static <T> Optional<T> fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            T result = COMPACT.fromJson(json, clazz);
            return Optional.ofNullable(result);
        } catch (JsonSyntaxException e) {
            log.error("Błąd deserializacji JSON do {}: {}", clazz.getSimpleName(), e.getMessage());
            log.debug("Błędny JSON (pierwsze 200 znaków): {}",
                    json.length() > 200 ? json.substring(0, 200) + "..." : json);
            return Optional.empty();
        }
    }

    /**
     * Deserializuje JSON reprezentujący tablicę do listy obiektów.
     *
     * @param json  string JSON z tablicą; {@code null}/pusty zwraca pustą listę
     * @param clazz klasa elementów listy
     * @param <T>   typ elementów
     * @return lista obiektów lub pusta lista przy błędzie
    */
    public static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            Type listType = TypeToken.getParameterized(List.class, clazz).getType();
            List<T> result = COMPACT.fromJson(json, listType);
            return result != null ? result : Collections.emptyList();
        } catch (JsonSyntaxException e) {
            log.error("Błąd deserializacji listy JSON do List<{}>: {}",
                    clazz.getSimpleName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parsuje string JSON do surowego {@link JsonElement} bez mapowania na klasę.
     * Przydatne gdy struktura odpowiedzi jest dynamiczna lub nieznana.
     *
     * @param json string JSON
     * @return {@link Optional} z elementem lub {@code empty()} przy błędzie
    */
    public static Optional<JsonElement> parseRaw(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.of(com.google.gson.JsonParser.parseString(json));
        } catch (JsonParseException e) {
            log.error("Błąd parsowania raw JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Sprawdza czy podany string jest poprawnym JSON-em.
     *
     * @param json string do walidacji
     * @return {@code true} jeśli JSON jest syntaktycznie poprawny
    */
    public static boolean isValidJson(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            com.google.gson.JsonParser.parseString(json);
            return true;
        } catch (JsonParseException e) {
            return false;
        }
    }

    /**
     * Konwertuje JSON kompaktowy na sformatowany (z wcięciami).
     * Przydatne do wyświetlania surowych odpowiedzi API w GUI.
     *
     * @param compactJson kompaktowy JSON
     * @return sformatowany JSON lub oryginalny string przy błędzie parsowania
    */
    public static String prettify(String compactJson) {
        if (compactJson == null) return "";
        try {
            JsonElement element = com.google.gson.JsonParser.parseString(compactJson);
            return PRETTY.toJson(element);
        } catch (JsonParseException e) {
            log.debug("Nie można sformatować JSON: {}", e.getMessage());
            return compactJson;
        }
    }

    /**
     * Buduje instancję {@link Gson} z obsługą {@link LocalDateTime} i opcjonalnymi wcięciami.
     *
     * @param pretty czy włączyć formatowanie z wcięciami
     * @return skonfigurowana instancja Gson
    */
    private static Gson buildGson(boolean pretty) {
        GsonBuilder builder = new GsonBuilder()
                .serializeNulls()
                .disableHtmlEscaping()
                // Serializacja LocalDateTime jako ISO string (np. "2024-06-14T12:00:00")
                .registerTypeAdapter(LocalDateTime.class,
                        (com.google.gson.JsonSerializer<LocalDateTime>)
                                (src, typeOfSrc, ctx) ->
                                        new com.google.gson.JsonPrimitive(src.toString()))
                // Deserializacja LocalDateTime z ISO string
                .registerTypeAdapter(LocalDateTime.class,
                        (com.google.gson.JsonDeserializer<LocalDateTime>)
                                (json, typeOfT, ctx) ->
                                        DateTimeUtil.parse(json.getAsString()).orElse(null));

        if (pretty) {
            builder.setPrettyPrinting();
        }

        return builder.create();
    }
    
    private JsonParser() { }

}