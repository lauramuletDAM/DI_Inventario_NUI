package es.dam.nui.smoketest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cliente minimo de la API de Groq (compatible con OpenAI): la parte de LENGUAJE de la interfaz
 * conversacional. Usa solo {@code java.net.http} y Jackson.
 *
 * <pre>{@code
 *   ask(clave, pregunta)   POST /openai/v1/chat/completions   {model, messages:[{role:user, content}], stream:false}
 *                          -> JSON  choices[0].message.content
 *
 *   Comprobaciones SIN gastar tokens:  GET /openai/v1/models
 *      sin clave  -> 401   el servicio es alcanzable (red, TLS, endpoint)      reachable()
 *      con clave  -> 200   la clave es valida y el modelo esta en la lista      checkKey(clave)
 * }</pre>
 *
 * <p>La clave NUNCA se escribe en codigo, en ficheros ni en logs: llega por parametro (campo de
 * contrasena de la ventana) o por la variable de entorno {@value #API_KEY_ENV}. El texto de la
 * pregunta sale a un servicio externo, a diferencia de Vosk, que es offline.
 *
 * <p>Modelo y endpoint verificados en la documentacion de Groq; pueden cambiar con el tiempo.
 */
final class GroqClient {

    static final String API_KEY_ENV = "GROQ_API_KEY";
    static final String MODEL = "openai/gpt-oss-20b";
    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final long TIMEOUT_SECONDS = 90;
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int MAX_ERROR_CHARS = 300;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private GroqClient() {
    }

    /** Sin clave la API debe responder 401: eso demuestra que el servicio es alcanzable. */
    static String reachable() throws Exception {
        HttpResponse<String> response = get("/models", null);
        if (response.statusCode() != HTTP_UNAUTHORIZED) {
            throw new IllegalStateException("se esperaba HTTP 401 sin clave y llego " + describe(response));
        }
        return "servicio alcanzable (HTTP 401 sin clave, como se espera)";
    }

    /** Con clave valida la API responde 200 con la lista de modelos; se exige que este el de {@link #MODEL}. */
    static String checkKey(String token) throws Exception {
        HttpResponse<String> response = get("/models", token);
        if (response.statusCode() != HTTP_OK) {
            throw new IllegalStateException(describe(response));
        }
        for (JsonNode model : JSON.readTree(response.body()).path("data")) {
            if (MODEL.equals(model.path("id").asText())) {
                return "clave valida y modelo " + MODEL + " disponible";
            }
        }
        throw new IllegalStateException("la clave es valida pero el modelo " + MODEL + " no esta disponible");
    }

    /** Envia una pregunta y devuelve el texto de la respuesta. */
    static String ask(String token, String question) throws Exception {
        String body = JSON.writeValueAsString(Map.of(
                "model", MODEL,
                "messages", List.of(Map.of("role", "user", "content", question)),
                "stream", false));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/chat/completions"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_OK) {
            throw new IllegalStateException(describe(response));
        }
        JsonNode content = JSON.readTree(response.body()).at("/choices/0/message/content");
        if (content.isMissingNode()) {
            throw new IllegalStateException("respuesta sin contenido: " + describe(response));
        }
        return content.asText();
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String describe(HttpResponse<String> response) {
        String body = response.body();
        return "HTTP " + response.statusCode() + ": "
                + (body.length() > MAX_ERROR_CHARS ? body.substring(0, MAX_ERROR_CHARS) + "..." : body);
    }
}
