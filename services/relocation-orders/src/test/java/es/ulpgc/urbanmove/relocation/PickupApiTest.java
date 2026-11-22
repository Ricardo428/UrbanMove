package es.ulpgc.urbanmove.relocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.relocation.availability.StateOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * POST /relocation-orders/{orderId}/pickup (docs/apis/api-relocation-orders.md, sección 3). Availability es un doble:
 * cada test decide qué responde y comprueba qué llamadas se hicieron y cuáles no. Las órdenes son de 4 SCOOTER.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class PickupApiTest extends AbstractOrderApiTest {

    // ---- Caso normal y resultado parcial ----

    @Test
    void anOperatorWithdrawsTheVehiclesAndTheOrderIsInProgressWithHim() {
        Order order = newOrder();
        String v1 = newVehicle();
        String v2 = newVehicle();
        String v3 = newVehicle();
        available(order, v1, v2, v3);
        putAnswers(new StateOutcome.Changed(order.from()));

        ResponseEntity<Map> response = pickup(order, "m-1", v1, v2, v3);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("orderId", order.id())
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("withdrawn", List.of(v1, v2, v3))
                .containsEntry("rejected", List.of());
        assertThat(detail(order)).containsEntry("operatorId", "m-1")
                .containsEntry("vehicleIds", List.of(v1, v2, v3));
        // WITHDRAWN sin zoneId: la zona no cambia al recoger.
        verify(availability).changeState(eq(v1), eq("WITHDRAWN"), isNull(), anyString());
    }

    @Test
    void aReservedVehicleIsRejectedWithoutAPutAndTheOthersAreWithdrawn() {
        Order order = newOrder();
        String free = newVehicle();
        String reserved = newVehicle();
        available(order, free);
        found(reserved, "SCOOTER", order.from(), "RESERVED");
        putAnswers(new StateOutcome.Changed(order.from()));

        ResponseEntity<Map> response = pickup(order, "m-1", free, reserved);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("withdrawn", List.of(free));
        assertThat(rejection(response, reserved)).contains("RESERVED");
        verify(availability, never()).changeState(eq(reserved), anyString(), any(), anyString());
        assertThat(detail(order)).containsEntry("vehicleIds", List.of(free));
    }

    // ---- Quién puede recoger ----

    @Test
    void anotherOperatorIsForbiddenAndDoesNotTouchAnyVehicle() {
        Order order = newOrder();
        String mine = newVehicle();
        available(order, mine);
        putAnswers(new StateOutcome.Changed(order.from()));
        pickup(order, "m-1", mine);
        String his = newVehicle();

        ResponseEntity<Map> response = pickup(order, "m-2", his);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Pierde en el UPDATE de la orden, antes de llegar a las bicis.
        verify(availability, never()).get(his);
        assertThat(detail(order)).containsEntry("operatorId", "m-1");
    }

    @Test
    void anAdminCannotPickUp() {
        Order order = newOrder();

        assertThat(pickup(order, "a-1", Role.ADMIN, newVehicle()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(detail(order)).containsEntry("status", "OPEN");
    }

    @Test
    void aCancelledOrderIsAConflict() {
        Order order = newOrder();
        orders.cancel(order.id());

        ResponseEntity<Map> response = pickup(order, "m-1", newVehicle());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) response.getBody().get("message")).contains("CANCELLED");
    }

    @Test
    void anEmptyListOrMoreThanQuantityIsABadRequest() {
        Order order = newOrder();

        assertThat(pickup(order, "m-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pickup(order, "m-1", newVehicle(), newVehicle(), newVehicle(), newVehicle(), newVehicle())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(detail(order)).containsEntry("status", "OPEN");
    }

    // ---- Comprobaciones del GET ----

    @Test
    void aVehicleOfAnotherZoneOrTypeIsRejectedAndTheOrderGoesBackToOpen() {
        Order order = newOrder();
        String elsewhere = newVehicle();
        String bike = newVehicle();
        found(elsewhere, "SCOOTER", "zone-otra", "AVAILABLE");
        found(bike, "BIKE", order.from(), "AVAILABLE");

        ResponseEntity<Map> response = pickup(order, "m-1", elsewhere, bike);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) response.getBody().get("message")).contains("zone-otra").contains("BIKE");
        verify(availability, never()).changeState(anyString(), anyString(), any(), anyString());
        assertThat(detail(order)).containsEntry("status", "OPEN").containsEntry("operatorId", null);
    }

    @Test
    void aVehicleWithdrawnByMaintenanceIsRejectedAlthoughThePutWouldAnswerOk() {
        Order order = newOrder();
        String withdrawnByOthers = newVehicle();
        found(withdrawnByOthers, "SCOOTER", order.from(), "WITHDRAWN");

        ResponseEntity<Map> response = pickup(order, "m-1", withdrawnByOthers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) response.getBody().get("message")).contains("WITHDRAWN");
        verify(availability, never()).changeState(anyString(), anyString(), any(), anyString());
    }

    // ---- La tabla relocation_order_vehicles ----

    @Test
    void aVehicleClaimedByAnotherActiveOrderIsRejectedWithoutCallingThePut() {
        String origin = newZone();
        Order first = newOrder(origin);
        Order second = newOrder(origin);
        String shared = newVehicle();
        // Las dos ven la bici AVAILABLE: el GET de la segunda llega antes del PUT de la primera.
        found(shared, "SCOOTER", origin, "AVAILABLE");
        putAnswers(new StateOutcome.Changed(origin));
        pickup(first, "m-1", shared);

        ResponseEntity<Map> response = pickup(second, "m-2", shared);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) response.getBody().get("message")).contains("otra orden");
        verify(availability, times(1)).changeState(eq(shared), anyString(), any(), anyString());
        assertThat(detail(second)).containsEntry("status", "OPEN");
    }

    @Test
    void aVehicleRejectedByAvailabilityIsReleasedAndTheOrderGoesBackToOpen() {
        Order order = newOrder();
        String vehicle = newVehicle();
        available(order, vehicle);
        putAnswers(new StateOutcome.Rejected("No se puede pasar de RESERVED a WITHDRAWN"));

        ResponseEntity<Map> response = pickup(order, "m-1", vehicle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(order)).containsEntry("status", "OPEN").containsEntry("vehicleIds", List.of());
        // Al soltarla, otra orden puede reclamarla.
        Order other = newOrder(order.from());
        putAnswers(new StateOutcome.Changed(order.from()));
        assertThat(pickup(other, "m-2", vehicle).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- Availability sin respuesta y reintento ----

    @Test
    void withoutAnswerToThePutTheClaimIsKeptAndTheOrderStaysInProgress() {
        Order order = newOrder();
        String vehicle = newVehicle();
        available(order, vehicle);
        putAnswers(new StateOutcome.Unreachable());

        ResponseEntity<Map> response = pickup(order, "m-1", vehicle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(detail(order)).containsEntry("status", "IN_PROGRESS")
                .containsEntry("operatorId", "m-1")
                .containsEntry("vehicleIds", List.of(vehicle));
    }

    @Test
    void theSameOperatorRepeatsThePickupAndTheUnansweredVehicleIsConfirmed() {
        Order order = newOrder();
        String ok = newVehicle();
        String lost = newVehicle();
        available(order, ok, lost);
        when(availability.changeState(eq(ok), anyString(), any(), anyString()))
                .thenReturn(new StateOutcome.Changed(order.from()));
        when(availability.changeState(eq(lost), anyString(), any(), anyString()))
                .thenReturn(new StateOutcome.Unreachable());

        ResponseEntity<Map> first = pickup(order, "m-1", ok, lost);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("withdrawn", List.of(ok));
        assertThat(rejection(first, lost)).contains("no respondió");

        // Al repetir, availability ya responde: las dos están WITHDRAWN y reclamadas por esta orden.
        found(ok, "SCOOTER", order.from(), "WITHDRAWN");
        found(lost, "SCOOTER", order.from(), "WITHDRAWN");
        when(availability.changeState(eq(lost), anyString(), any(), anyString()))
                .thenReturn(new StateOutcome.Changed(order.from()));

        ResponseEntity<Map> again = pickup(order, "m-1", ok, lost);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("withdrawn", List.of(ok, lost))
                .containsEntry("rejected", List.of());
        assertThat(detail(order)).containsEntry("vehicleIds", List.of(ok, lost));
    }

    @Test
    void aRetryCannotGoBeyondTheQuantityOfTheOrder() {
        Order order = newOrder();
        String v1 = newVehicle();
        String v2 = newVehicle();
        String v3 = newVehicle();
        String v4 = newVehicle();
        String v5 = newVehicle();
        available(order, v1, v2, v3, v4, v5);
        putAnswers(new StateOutcome.Changed(order.from()));
        pickup(order, "m-1", v1, v2, v3, v4);

        ResponseEntity<Map> again = pickup(order, "m-1", v5);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) again.getBody().get("message")).contains("ya tiene sus 4");
        assertThat(detail(order)).containsEntry("status", "IN_PROGRESS")
                .containsEntry("vehicleIds", List.of(v1, v2, v3, v4));
    }

    // ---- Ayudas ----

    private static String rejection(ResponseEntity<Map> response, String vehicleId) {
        return ((List<Map>) response.getBody().get("rejected")).stream()
                .filter(r -> vehicleId.equals(r.get("vehicleId")))
                .map(r -> (String) r.get("reason"))
                .findFirst().orElseThrow();
    }
}
