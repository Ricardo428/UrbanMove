package es.ulpgc.urbanmove.maintenance.availability;

import es.ulpgc.urbanmove.common.client.ServiceRestClients;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.maintenance.MaintenanceProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Llamada síncrona a availability para cambiar el estado de un recurso. Se autentica con un token de servicio con
 * rol ADMIN que firma el propio servicio con el secreto JWT compartido (ADR-006). El token, el X-Correlation-Id y el
 * timeout los pone ServiceRestClients (common). El PUT es idempotente: repetirlo con el mismo estado responde 200.
 */
@Component
public class AvailabilityClient {

    static final String SERVICE_ID = "svc-maintenance-orders";

    private static final Logger log = LoggerFactory.getLogger(AvailabilityClient.class);

    private final RestClient rest;

    public AvailabilityClient(RestClient.Builder builder, MaintenanceProperties properties, JwtTokenService tokens) {
        MaintenanceProperties.Availability availability = properties.availability();
        this.rest = ServiceRestClients.create(builder, availability.url(), availability.timeout(), SERVICE_ID,
                tokens);
    }

    public StateOutcome changeState(String vehicleId, String state, String reason) {
        try {
            return rest.put()
                    .uri("/api/v1/availability/{vehicleId}/state", vehicleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("state", state, "reason", reason))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new StateOutcome.Changed(response.bodyTo(StateResponse.class).zoneId());
                        }
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return new StateOutcome.NotFound();
                        }
                        if (status == HttpStatus.CONFLICT.value()) {
                            return new StateOutcome.Rejected(messageOf(response.bodyTo(ErrorBody.class)));
                        }
                        // 401/403 indican un secreto JWT distinto entre servicios; 5xx, un fallo de availability.
                        log.error("Respuesta inesperada de availability al pasar {} a {}: {}", vehicleId, state,
                                status);
                        return new StateOutcome.Unreachable();
                    });
        } catch (ResourceAccessException e) {
            log.warn("Availability no responde al pasar {} a {}: {}", vehicleId, state, e.getMessage());
            return new StateOutcome.Unreachable();
        }
    }

    private static String messageOf(ErrorBody body) {
        return body == null || body.message() == null ? "transición no permitida" : body.message();
    }

    /** Lo que responde availability al cambiar el estado; solo interesa la zona. */
    record StateResponse(String vehicleId, String status, String zoneId) {
    }

    /** El formato de error común (docs/apis); solo interesa el mensaje. */
    record ErrorBody(String error, String message, String correlationId) {
    }
}
