package es.ulpgc.urbanmove.relocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import es.ulpgc.urbanmove.relocation.availability.CountOutcome;
import es.ulpgc.urbanmove.relocation.order.RelocationOrderService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Listar, ver y cancelar órdenes (docs/apis/api-relocation-orders.md, secciones 1, 2 y 5), con sus roles. */
@SuppressWarnings({"rawtypes", "unchecked"})
class RelocationOrderApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/relocation-orders";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private RelocationOrderService orders;

    // ---- Listar y ver ----

    @Test
    void anOperatorSeesAnOpenOrderInTheListAndInDetailWithoutOperatorNorVehicles() {
        String orderId = newOrder();

        ResponseEntity<List> list = rest.exchange(BASE + "?status=OPEN", HttpMethod.GET,
                as("m-1", Role.MAINTENANCE), List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>) list.getBody()).extracting(order -> order.get("orderId")).contains(orderId);

        ResponseEntity<Map> detail = get(orderId, Role.MAINTENANCE);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsEntry("status", "OPEN")
                .containsEntry("vehicleType", "SCOOTER")
                .containsEntry("quantity", 4)
                .containsEntry("operatorId", null)
                .containsEntry("vehicleIds", List.of())
                .containsEntry("pickedUpAt", null);
    }

    @Test
    void anAdminCanAlsoSeeTheOrders() {
        assertThat(get(newOrder(), Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aUserCannotSeeTheOrders() {
        assertThat(get(newOrder(), Role.USER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withoutTokenTheAnswerIsUnauthorized() {
        assertThat(rest.getForEntity(BASE, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownStatusFilterIsABadRequest() {
        assertThat(rest.exchange(BASE + "?status=DONE", HttpMethod.GET, as("m-1", Role.MAINTENANCE), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anUnknownOrMalformedOrderIsNotFound() {
        assertThat(get("ro-999999", Role.MAINTENANCE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("mo-210", Role.MAINTENANCE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- Cancelar ----

    @Test
    void anAdminCancelsAnOpenOrderAndTheZoneCanGetANewOne() {
        String zone = newZone();
        String first = newOrder(zone);

        ResponseEntity<Map> cancelled = cancel(first, Role.ADMIN);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("orderId", first).containsEntry("status", "CANCELLED");
        // Ya no está activa: sale del índice parcial y un hotspot nuevo de la misma zona crea otra orden.
        String second = newOrder(zone);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void cancellingTwiceIsAConflict() {
        String orderId = newOrder();
        cancel(orderId, Role.ADMIN);

        ResponseEntity<Map> again = cancel(orderId, Role.ADMIN);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) again.getBody().get("message")).contains("CANCELLED");
    }

    @Test
    void anOperatorCannotCancel() {
        String orderId = newOrder();

        assertThat(cancel(orderId, Role.MAINTENANCE).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(orderId, Role.ADMIN).getBody()).containsEntry("status", "OPEN");
    }

    // ---- Ayudas ----

    private String newOrder() {
        return newOrder(newZone());
    }

    /** Crea una orden OPEN de 4 SCOOTER hacia zone, como lo haría el consumidor con un hotspot. */
    private String newOrder(String zone) {
        when(availability.countAvailableByZone(VehicleType.SCOOTER))
                .thenReturn(new CountOutcome.Counted(Map.of(zone, 2, newZone(), 8)));
        Instant now = Instant.parse("2026-10-13T16:00:00Z");
        return orders.createFromHotspot(new DemandHotspotDetected(zone, VehicleType.SCOOTER, 2, 12,
                now.minusSeconds(3600), now)).orElseThrow();
    }

    private ResponseEntity<Map> get(String orderId, Role role) {
        return rest.exchange(BASE + "/" + orderId, HttpMethod.GET, as("m-1", role), Map.class);
    }

    private ResponseEntity<Map> cancel(String orderId, Role role) {
        return rest.exchange(BASE + "/" + orderId + "/cancel", HttpMethod.POST, as("a-1", role), Map.class);
    }

    private HttpEntity<Object> as(String userId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(userId, role));
        return new HttpEntity<>(null, headers);
    }

    private static String newZone() {
        return "zone-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
