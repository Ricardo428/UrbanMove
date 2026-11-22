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

import es.ulpgc.urbanmove.relocation.availability.StateOutcome;
import es.ulpgc.urbanmove.relocation.availability.VehicleOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * POST /relocation-orders/{orderId}/complete (docs/apis/api-relocation-orders.md, sección 4). Cada orden se recoge
 * primero por la API, como en la App de Mantenimiento; después el doble de availability dice cómo está cada recurso
 * (decisión D) y se mira qué PUT se pidieron y qué evento quedó en outbox_events.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class CompleteApiTest extends AbstractOrderApiTest {

    // ---- Caso normal ----

    @Test
    void theOperatorLeavesTheVehiclesInTheDestinationAndTheEventIsPublished() {
        Order order = newOrder();
        String v1 = newVehicle();
        String v2 = newVehicle();
        pickedUp(order, "m-1", v1, v2);

        ResponseEntity<Map> response = complete(order, "m-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("orderId", order.id())
                .containsEntry("status", "COMPLETED")
                .containsEntry("vehicleIds", List.of(v1, v2))
                .containsEntry("toZoneId", order.to());
        assertThat(response.getBody().get("completedAt")).isNotNull();
        // WITHDRAWN → READY_FOR_SERVICE en la zona destino → AVAILABLE.
        verify(availability).changeState(eq(v1), eq("READY_FOR_SERVICE"), eq(order.to()), anyString());
        verify(availability).changeState(eq(v1), eq("AVAILABLE"), isNull(), anyString());
        assertThat(completedEvents(order)).containsExactly(
                Map.of("vehicle_ids", "[\"" + v1 + "\", \"" + v2 + "\"]", "to_zone", order.to()));
    }

    @Test
    void afterCompletingTheVehicleCanEnterAnotherOrder() {
        Order order = newOrder();
        String vehicle = newVehicle();
        pickedUp(order, "m-1", vehicle);
        complete(order, "m-1");

        // Ya está AVAILABLE en el destino: una orden que sale de esa zona puede reclamarla.
        Order next = newOrder(order.to());
        found(vehicle, "SCOOTER", order.to(), "AVAILABLE");

        assertThat(pickup(next, "m-2", vehicle).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- Quién puede completar ----

    @Test
    void anotherOperatorIsForbiddenAndNoVehicleIsTouched() {
        Order order = newOrder();
        String vehicle = newVehicle();
        pickedUp(order, "m-1", vehicle);

        ResponseEntity<Map> response = complete(order, "m-2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(availability, never()).changeState(anyString(), eq("READY_FOR_SERVICE"), any(), anyString());
        assertThat(detail(order)).containsEntry("status", "IN_PROGRESS");
        assertThat(completedEvents(order)).isEmpty();
    }

    @Test
    void anOpenOrderCannotBeCompleted() {
        Order order = newOrder();

        assertThat(complete(order, "m-1").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anOrderInProgressWithoutVehiclesCannotBeCompleted() {
        Order order = newOrder();
        String vehicle = newVehicle();
        // El GET del pickup no respondió: la orden quedó IN_PROGRESS sin ningún recurso reclamado.
        when(availability.get(vehicle)).thenReturn(new VehicleOutcome.Unreachable());
        assertThat(pickup(order, "m-1", vehicle).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        ResponseEntity<Map> response = complete(order, "m-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) response.getBody().get("message")).contains("no tiene recursos");
        assertThat(completedEvents(order)).isEmpty();
    }

    // ---- Reintentos (decisiones C y D) ----

    @Test
    void repeatingAfterCompletedReturnsTheOrderWithoutCallingAvailabilityNorAnotherEvent() {
        Order order = newOrder();
        String vehicle = newVehicle();
        pickedUp(order, "m-1", vehicle);
        complete(order, "m-1");

        ResponseEntity<Map> again = complete(order, "m-1");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("status", "COMPLETED");
        verify(availability, times(1)).changeState(eq(vehicle), eq("READY_FOR_SERVICE"), any(), anyString());
        assertThat(completedEvents(order)).hasSize(1);
    }

    @Test
    void aVehicleAlreadyReadyForServiceInTheDestinationOnlyGoesToAvailable() {
        Order order = newOrder();
        String vehicle = newVehicle();
        pickedUp(order, "m-1", vehicle);
        found(vehicle, "SCOOTER", order.to(), "READY_FOR_SERVICE");

        assertThat(complete(order, "m-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(availability, never()).changeState(anyString(), eq("READY_FOR_SERVICE"), any(), anyString());
        verify(availability).changeState(eq(vehicle), eq("AVAILABLE"), isNull(), anyString());
    }

    @Test
    void aVehicleAlreadyDeliveredAndReservedIsNotTouched() {
        Order order = newOrder();
        String vehicle = newVehicle();
        pickedUp(order, "m-1", vehicle);
        // Un intento anterior la dejó AVAILABLE y un usuario la reservó antes del reintento.
        found(vehicle, "SCOOTER", order.to(), "RESERVED");

        assertThat(complete(order, "m-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(availability, never()).changeState(anyString(), eq("READY_FOR_SERVICE"), any(), anyString());
        verify(availability, never()).changeState(anyString(), eq("AVAILABLE"), any(), anyString());
        assertThat(completedEvents(order)).hasSize(1);
    }

    // ---- Availability falla a mitad ----

    @Test
    void ifAvailabilityStopsAnsweringMidwayTheOrderStaysInProgressAndTheRetryFinishesIt() {
        Order order = newOrder();
        String first = newVehicle();
        String second = newVehicle();
        pickedUp(order, "m-1", first, second);
        when(availability.get(second)).thenReturn(new VehicleOutcome.Unreachable());

        ResponseEntity<Map> cut = complete(order, "m-1");

        assertThat(cut.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(detail(order)).containsEntry("status", "IN_PROGRESS");
        assertThat(completedEvents(order)).isEmpty();

        // Al repetir: la primera ya está AVAILABLE en el destino y la segunda sigue WITHDRAWN.
        found(first, "SCOOTER", order.to(), "AVAILABLE");
        found(second, "SCOOTER", order.from(), "WITHDRAWN");

        assertThat(complete(order, "m-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(availability, times(1)).changeState(eq(first), eq("READY_FOR_SERVICE"), any(), anyString());
        verify(availability, times(1)).changeState(eq(second), eq("READY_FOR_SERVICE"), any(), anyString());
        assertThat(completedEvents(order)).hasSize(1);
    }

    @Test
    void aTransitionRejectedByAvailabilityIsAConflictAndTheOrderStaysInProgress() {
        Order order = newOrder();
        String vehicle = newVehicle();
        pickedUp(order, "m-1", vehicle);
        putAnswers(new StateOutcome.Rejected("No se puede pasar de CHARGING a READY_FOR_SERVICE"));

        ResponseEntity<Map> response = complete(order, "m-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(order)).containsEntry("status", "IN_PROGRESS");
        assertThat(completedEvents(order)).isEmpty();
    }

    // ---- Ayudas ----

    /**
     * Recoge la orden por la API y deja el doble como estaría después: los recursos WITHDRAWN en la zona origen y
     * availability aceptando los siguientes PUT.
     */
    private void pickedUp(Order order, String operatorId, String... vehicleIds) {
        available(order, vehicleIds);
        putAnswers(new StateOutcome.Changed(order.from()));
        assertThat(pickup(order, operatorId, vehicleIds).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String vehicleId : vehicleIds) {
            found(vehicleId, "SCOOTER", order.from(), "WITHDRAWN");
        }
    }
}
