package es.ulpgc.urbanmove.rides.zones;

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
 * Consulta síncrona de una zona en zones, para comprobar que se puede aparcar en el destino de un viaje.
 * Token de servicio (ADR-006), X-Correlation-Id y timeout: ServiceRestClients (common).
 */
@Component
public class ZonesClient {

    private static final Logger log = LoggerFactory.getLogger(ZonesClient.class);

    private final RestClient rest;

    public ZonesClient(RestClient.Builder builder, RidesProperties properties, JwtTokenService tokens) {
        RidesProperties.Downstream zones = properties.zones();
        this.rest = ServiceRestClients.create(builder, zones.url(), zones.timeout(), RidesServiceAccount.ID, tokens);
    }

    public ZoneLookup find(String zoneId) {
        try {
            return rest.get()
                    .uri("/api/v1/zones/{zoneId}", zoneId)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.OK.value()) {
                            return new ZoneLookup.Found(response.bodyTo(ZoneSnapshot.class));
                        }
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return new ZoneLookup.NotFound();
                        }
                        // 401/403 indican un secreto JWT distinto entre servicios; 5xx, un fallo de zones.
                        log.error("Respuesta inesperada de zones al consultar {}: {}", zoneId, status);
                        return new ZoneLookup.Unreachable();
                    });
        } catch (ResourceAccessException e) {
            log.warn("Zones no responde al consultar {}: {}", zoneId, e.getMessage());
            return new ZoneLookup.Unreachable();
        }
    }
}
