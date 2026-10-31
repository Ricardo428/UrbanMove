package es.ulpgc.urbanmove.reservations.availability;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import es.ulpgc.urbanmove.reservations.ReservationsProperties;
import java.net.http.HttpClient;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Llamada síncrona a availability para reservar un vehículo. Se autentica con un token de servicio con rol ADMIN
 * que firma el propio servicio con el secreto JWT compartido (ADR-006), y propaga el X-Correlation-Id.
 */
@Component
public class AvailabilityClient {

    static final String SERVICE_ID = "svc-reservations";

    private static final Logger log = LoggerFactory.getLogger(AvailabilityClient.class);

    private final RestClient rest;
    private final JwtTokenService tokens;

    public AvailabilityClient(RestClient.Builder builder, ReservationsProperties properties, JwtTokenService tokens) {
        ReservationsProperties.Availability availability = properties.availability();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(availability.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(availability.timeout());
        this.rest = builder.baseUrl(availability.url()).requestFactory(requestFactory).build();
        this.tokens = tokens;
    }

    public ReserveOutcome reserve(String vehicleId, String reservationId) {
        try {
            return rest.post()
                    .uri("/api/v1/availability/{vehicleId}/reserve", vehicleId)
                    .headers(headers -> {
                        headers.setBearerAuth(tokens.generate(SERVICE_ID, Role.ADMIN));
                        String correlationId = CorrelationIdFilter.current();
                        if (correlationId != null) {
                            headers.set(CorrelationIdFilter.HEADER, correlationId);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("reservationId", reservationId))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new ReserveOutcome.Reserved(response.bodyTo(ReserveResponse.class).zoneId());
                        }
                        return toFailure(vehicleId, status);
                    });
        } catch (ResourceAccessException e) {
            log.warn("Availability no responde al reservar {}: {}", vehicleId, e.getMessage());
            return new ReserveOutcome.Unreachable();
        }
    }

    private static ReserveOutcome toFailure(String vehicleId, int status) {
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new ReserveOutcome.VehicleNotFound();
        }
        if (status == HttpStatus.CONFLICT.value()) {
            return new ReserveOutcome.NotAvailable();
        }
        // 401/403 indican un secreto JWT distinto entre servicios; 5xx, un fallo de availability.
        log.error("Respuesta inesperada de availability al reservar {}: {}", vehicleId, status);
        return new ReserveOutcome.Unreachable();
    }

    /** Lo que responde availability; solo interesa la zona. */
    record ReserveResponse(String vehicleId, String status, String reservationId, String zoneId) {
    }
}
