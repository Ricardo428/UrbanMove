package es.ulpgc.urbanmove.relocation.availability;

import es.ulpgc.urbanmove.common.client.ServiceRestClients;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.relocation.RelocationProperties;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Llamadas síncronas a availability, dueña del estado de los recursos. Se autentica con un token de servicio con
 * rol ADMIN que firma el propio servicio con el secreto JWT compartido (ADR-006): al crear una orden desde el
 * consumidor no hay ningún usuario cuyo token usar. El token, el X-Correlation-Id y el timeout los pone
 * ServiceRestClients (common).
 */
@Component
public class AvailabilityClient {

    static final String SERVICE_ID = "svc-relocation-orders";

    private static final Logger log = LoggerFactory.getLogger(AvailabilityClient.class);

    private final RestClient rest;

    public AvailabilityClient(RestClient.Builder builder, RelocationProperties properties, JwtTokenService tokens) {
        RelocationProperties.Availability availability = properties.availability();
        this.rest = ServiceRestClients.create(builder, availability.url(), availability.timeout(), SERVICE_ID,
                tokens);
    }

    /**
     * Cuenta los recursos AVAILABLE de un tipo en cada zona, con GET /api/v1/availability?type= (sin zoneId).
     * Es el recuento actual: el availableCount de DemandHotspotDetected puede tener minutos.
     */
    public CountOutcome countAvailableByZone(VehicleType type) {
        try {
            return rest.get()
                    .uri(uri -> uri.path("/api/v1/availability").queryParam("type", type.name()).build())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new CountOutcome.Counted(countByZone(response.bodyTo(AvailableVehicle[].class)));
                        }
                        // 401/403 indican un secreto JWT distinto entre servicios; 5xx, un fallo de availability.
                        log.error("Respuesta inesperada de availability al contar los {} disponibles: {}", type,
                                status);
                        return new CountOutcome.Unreachable();
                    });
        } catch (ResourceAccessException e) {
            log.warn("Availability no responde al contar los {} disponibles: {}", type, e.getMessage());
            return new CountOutcome.Unreachable();
        }
    }

    /**
     * Estado, tipo y zona actuales de un recurso. /pickup lo consulta antes del PUT porque el PUT /state solo mira
     * el estado, no la zona ni el tipo, y es idempotente: un recurso que ya está WITHDRAWN pasaría por bueno.
     */
    public VehicleOutcome get(String vehicleId) {
        try {
            return rest.get()
                    .uri("/api/v1/availability/{vehicleId}", vehicleId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            AvailableVehicle body = response.bodyTo(AvailableVehicle.class);
                            return new VehicleOutcome.Found(body.vehicleId(), body.vehicleType(), body.zoneId(),
                                    body.status());
                        }
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return new VehicleOutcome.NotFound();
                        }
                        log.error("Respuesta inesperada de availability al consultar {}: {}", vehicleId, status);
                        return new VehicleOutcome.Unreachable();
                    });
        } catch (ResourceAccessException e) {
            log.warn("Availability no responde al consultar {}: {}", vehicleId, e.getMessage());
            return new VehicleOutcome.Unreachable();
        }
    }

    /**
     * Pide una transición de estado. zoneId solo se manda al mover el recurso de zona (READY_FOR_SERVICE en la
     * zona destino al completar); null la deja como está. reason solo queda en el log de availability.
     */
    public StateOutcome changeState(String vehicleId, String state, String zoneId, String reason) {
        try {
            return rest.put()
                    .uri("/api/v1/availability/{vehicleId}/state", vehicleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChangeStateRequest(state, reason, zoneId))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new StateOutcome.Changed(response.bodyTo(AvailableVehicle.class).zoneId());
                        }
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return new StateOutcome.NotFound();
                        }
                        if (status == HttpStatus.CONFLICT.value()) {
                            return new StateOutcome.Rejected(messageOf(response.bodyTo(ErrorBody.class)));
                        }
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

    private static Map<String, Integer> countByZone(AvailableVehicle[] vehicles) {
        if (vehicles == null) {
            return Map.of();
        }
        return Arrays.stream(vehicles)
                .collect(Collectors.groupingBy(AvailableVehicle::zoneId, Collectors.summingInt(vehicle -> 1)));
    }

    /** Un recurso como lo devuelve availability (en la lista, en el GET de uno y en la respuesta del PUT). */
    record AvailableVehicle(String vehicleId, String vehicleType, String zoneId, String status) {
    }

    /** Cuerpo del PUT /state. Un zoneId null no cambia la zona. */
    record ChangeStateRequest(String state, String reason, String zoneId) {
    }

    /** El formato de error común (docs/apis); solo interesa el mensaje. */
    record ErrorBody(String error, String message, String correlationId) {
    }
}
