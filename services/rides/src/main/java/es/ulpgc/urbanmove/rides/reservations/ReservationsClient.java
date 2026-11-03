package es.ulpgc.urbanmove.rides.reservations;

import es.ulpgc.urbanmove.common.client.ServiceRestClients;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.rides.RidesProperties;
import es.ulpgc.urbanmove.rides.RidesServiceAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Consulta síncrona de una reserva en reservations, para comprobar que se puede iniciar un viaje con ella.
 * El token de servicio de rides tiene rol ADMIN (ADR-006) y puede leer cualquier reserva: las comprobaciones de
 * usuario, vehículo y estado las hace rides con el resultado. Token, X-Correlation-Id y timeout: ServiceRestClients.
 */
@Component
public class ReservationsClient {

    private static final Logger log = LoggerFactory.getLogger(ReservationsClient.class);

    private final RestClient rest;

    public ReservationsClient(RestClient.Builder builder, RidesProperties properties, JwtTokenService tokens) {
        RidesProperties.Downstream reservations = properties.reservations();
        this.rest = ServiceRestClients.create(builder, reservations.url(), reservations.timeout(),
                RidesServiceAccount.ID, tokens);
    }

    public ReservationLookup find(String reservationId) {
        try {
            return rest.get()
                    .uri("/api/v1/reservations/{reservationId}", reservationId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new ReservationLookup.Found(response.bodyTo(ReservationSnapshot.class));
                        }
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return new ReservationLookup.NotFound();
                        }
                        // 401/403 indican un secreto JWT distinto entre servicios; 5xx, un fallo de reservations.
                        log.error("Respuesta inesperada de reservations al consultar {}: {}", reservationId, status);
                        return new ReservationLookup.Unreachable();
                    });
        } catch (ResourceAccessException e) {
            log.warn("Reservations no responde al consultar {}: {}", reservationId, e.getMessage());
            return new ReservationLookup.Unreachable();
        }
    }
}
