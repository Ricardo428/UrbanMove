package es.ulpgc.urbanmove.rides.availability;

import es.ulpgc.urbanmove.common.client.ServiceRestClients;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.rides.RidesProperties;
import es.ulpgc.urbanmove.rides.RidesServiceAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Llamada síncrona a availability para poner un vehículo IN_USE al iniciar un viaje. El token de servicio,
 * el X-Correlation-Id y el timeout los pone ServiceRestClients (common).
 */
@Component
public class AvailabilityClient {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityClient.class);

    private final RestClient rest;

    public AvailabilityClient(RestClient.Builder builder, RidesProperties properties, JwtTokenService tokens) {
        RidesProperties.Downstream availability = properties.availability();
        this.rest = ServiceRestClients.create(builder, availability.url(), availability.timeout(),
                RidesServiceAccount.ID, tokens);
    }

    /** reservationId es null si el viaje empieza sin reserva. */
    public StartOutcome start(String vehicleId, String rideId, String reservationId) {
        try {
            return rest.post()
                    .uri("/api/v1/availability/{vehicleId}/start", vehicleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new StartRequest(rideId, reservationId))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new StartOutcome.Started(response.bodyTo(StartResponse.class).zoneId());
                        }
                        return toFailure(vehicleId, status);
                    });
        } catch (ResourceAccessException e) {
            log.warn("Availability no responde al iniciar el viaje con {}: {}", vehicleId, e.getMessage());
            return new StartOutcome.Unreachable();
        }
    }

    private static StartOutcome toFailure(String vehicleId, int status) {
        if (status == HttpStatus.NOT_FOUND.value()) {
            return new StartOutcome.VehicleNotFound();
        }
        if (status == HttpStatus.CONFLICT.value()) {
            return new StartOutcome.NotAvailable();
        }
        // 401/403 indican un secreto JWT distinto entre servicios; 5xx, un fallo de availability.
        log.error("Respuesta inesperada de availability al iniciar el viaje con {}: {}", vehicleId, status);
        return new StartOutcome.Unreachable();
    }

    /** Lo que se envía a availability (docs/apis/api-availability.md, sección 4). */
    record StartRequest(String rideId, String reservationId) {
    }

    /** Lo que responde availability; solo interesa la zona. */
    record StartResponse(String vehicleId, String status, String zoneId) {
    }
}
