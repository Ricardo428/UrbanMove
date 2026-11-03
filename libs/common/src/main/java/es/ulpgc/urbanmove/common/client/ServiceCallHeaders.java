package es.ulpgc.urbanmove.common.client;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Añade a cada llamada entre servicios el token de servicio con rol ADMIN (ADR-006) y, si la petición en curso
 * lo tiene, el X-Correlation-Id. El token se firma en cada llamada, así que nunca llega caducado.
 */
class ServiceCallHeaders implements ClientHttpRequestInterceptor {

    private final String serviceId;
    private final JwtTokenService tokens;

    ServiceCallHeaders(String serviceId, JwtTokenService tokens) {
        this.serviceId = serviceId;
        this.tokens = tokens;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(tokens.generate(serviceId, Role.ADMIN));
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
