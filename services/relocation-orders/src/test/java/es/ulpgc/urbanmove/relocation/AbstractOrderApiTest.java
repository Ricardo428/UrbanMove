package es.ulpgc.urbanmove.relocation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import es.ulpgc.urbanmove.relocation.availability.CountOutcome;
import es.ulpgc.urbanmove.relocation.availability.StateOutcome;
import es.ulpgc.urbanmove.relocation.availability.VehicleOutcome;
import es.ulpgc.urbanmove.relocation.order.RelocationOrderService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ayudas comunes de los tests de /pickup y /complete: crear órdenes de 4 SCOOTER, decidir qué responde el doble de
 * availability y llamar a la API con el token de un rol. Ids de recurso y de zona aleatorios: la base de datos y sus
 * índices únicos se comparten entre tests.
 */
@SuppressWarnings("rawtypes")
abstract class AbstractOrderApiTest extends AbstractIntegrationTest {

    static final String BASE = "/api/v1/relocation-orders";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JwtTokenService tokens;

    @Autowired
    RelocationOrderService orders;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Sin reintentos automáticos: HttpClient 5 repite una vez por su cuenta las respuestas 503, y entonces una
     * llamada del test serían dos peticiones al servicio. La App de Mantenimiento no reintenta sola: lo decide el
     * operario.
     */
    @BeforeEach
    void oneCallIsOneRequest() {
        rest.getRestTemplate().setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom().disableAutomaticRetries().build()));
    }

    /** Una orden OPEN de 4 SCOOTER de from a to. */
    record Order(String id, String from, String to) {
    }

    Order newOrder() {
        return newOrder(newZone());
    }

    /** Crea una orden OPEN hacia una zona nueva, con from como única zona que puede ceder (8 disponibles). */
    Order newOrder(String from) {
        String to = newZone();
        when(availability.countAvailableByZone(VehicleType.SCOOTER))
                .thenReturn(new CountOutcome.Counted(Map.of(to, 2, from, 8)));
        Instant now = Instant.parse("2026-10-13T16:00:00Z");
        String id = orders.createFromHotspot(new DemandHotspotDetected(to, VehicleType.SCOOTER, 2, 12,
                now.minusSeconds(3600), now)).orElseThrow();
        return new Order(id, from, to);
    }

    void available(Order order, String... vehicleIds) {
        for (String vehicleId : vehicleIds) {
            found(vehicleId, "SCOOTER", order.from(), "AVAILABLE");
        }
    }

    void found(String vehicleId, String type, String zoneId, String status) {
        when(availability.get(vehicleId)).thenReturn(new VehicleOutcome.Found(vehicleId, type, zoneId, status));
    }

    void putAnswers(StateOutcome outcome) {
        when(availability.changeState(anyString(), anyString(), any(), anyString())).thenReturn(outcome);
    }

    ResponseEntity<Map> pickup(Order order, String operatorId, String... vehicleIds) {
        return pickup(order, operatorId, Role.MAINTENANCE, vehicleIds);
    }

    ResponseEntity<Map> pickup(Order order, String userId, Role role, String... vehicleIds) {
        return rest.exchange(BASE + "/" + order.id() + "/pickup", HttpMethod.POST,
                new HttpEntity<>(Map.of("vehicleIds", List.of(vehicleIds)), bearer(userId, role)), Map.class);
    }

    ResponseEntity<Map> complete(Order order, String operatorId) {
        return rest.exchange(BASE + "/" + order.id() + "/complete", HttpMethod.POST,
                new HttpEntity<>(bearer(operatorId, Role.MAINTENANCE)), Map.class);
    }

    Map detail(Order order) {
        return rest.exchange(BASE + "/" + order.id(), HttpMethod.GET, new HttpEntity<>(bearer("a-1", Role.ADMIN)),
                Map.class).getBody();
    }

    /** Los RelocationOrderCompleted de la orden que hay en outbox_events. */
    List<Map<String, Object>> completedEvents(Order order) {
        return jdbc.queryForList("""
                        SELECT body -> 'payload' ->> 'vehicleIds' AS vehicle_ids,
                               body -> 'payload' ->> 'toZoneId' AS to_zone
                          FROM outbox_events
                         WHERE event_type = 'RelocationOrderCompleted' AND body -> 'payload' ->> 'orderId' = ?
                        """,
                order.id());
    }

    HttpHeaders bearer(String userId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(userId, role));
        return headers;
    }

    static String newVehicle() {
        return "v-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String newZone() {
        return "zone-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
