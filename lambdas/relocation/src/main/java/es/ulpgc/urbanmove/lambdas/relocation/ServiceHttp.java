package es.ulpgc.urbanmove.lambdas.relocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ulpgc.urbanmove.events.EventJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Llamadas GET a un servicio con el token de servicio y el X-Correlation-Id de la ejecución, como hace
 * ServiceRestClients en los servicios (ADR-006). Cualquier respuesta que no sea 200 es una UpstreamException.
 */
public final class ServiceHttp {

    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Fechas ISO 8601 y campos desconocidos ignorados: un campo nuevo en la respuesta no rompe la Lambda. */
    private static final ObjectMapper JSON = EventJson.mapper();

    private final String serviceName;
    private final String baseUrl;
    private final HttpClient http;
    private final ServiceToken tokens;

    public ServiceHttp(String serviceName, String baseUrl, HttpClient http, ServiceToken tokens) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Falta la URL de " + serviceName);
        }
        this.serviceName = serviceName;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.tokens = tokens;
    }

    public <T> T get(String pathAndQuery, Class<T> responseType, String correlationId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + tokens.sign())
                .header(CORRELATION_HEADER, correlationId)
                .GET()
                .build();
        HttpResponse<String> response = send(request, pathAndQuery);
        if (response.statusCode() != 200) {
            // 401/403 indican un secreto JWT distinto; 5xx, un fallo del servicio.
            throw new UpstreamException(
                    serviceName + " respondió HTTP " + response.statusCode() + " a GET " + pathAndQuery);
        }
        try {
            return JSON.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new UpstreamException("Respuesta de " + serviceName + " no válida: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String pathAndQuery) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UpstreamException(
                    serviceName + " no responde a GET " + pathAndQuery + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Ejecución interrumpida", e);
        }
    }
}
