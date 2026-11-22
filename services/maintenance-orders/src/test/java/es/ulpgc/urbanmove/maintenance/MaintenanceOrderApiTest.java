package es.ulpgc.urbanmove.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventType;
import es.ulpgc.urbanmove.events.MaintenanceReason;
import es.ulpgc.urbanmove.events.payload.VehicleBatteryLow;
import es.ulpgc.urbanmove.maintenance.availability.StateOutcome;
import es.ulpgc.urbanmove.messaging.EventHandler;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class MaintenanceOrderApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/maintenance-orders";
    private static final Instant DETECTED = Instant.parse("2026-10-13T11:00:02Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private EventHandler<VehicleBatteryLow> batteryLow;

    // ---- Cómo nace una orden ----

    @Test
    void aDetectionOpensAnOrderWithTheDataOfTheEvent() {
        String vehicle = newId("v");

        detect(vehicle, MaintenanceReason.BATTERY_LOW, 14, DETECTED);

        Map order = onlyOrderOf(vehicle);
        assertThat(order).containsEntry("status", "OPEN")
                .containsEntry("reason", "BATTERY_LOW")
                .containsEntry("batteryLevelPercent", 14)
                .containsEntry("detectedAt", "2026-10-13T11:00:02Z");
        assertThat((String) order.get("orderId")).startsWith("mo-");
    }

    @Test
    void aSecondDetectionOfTheSameVehicleWhileTheOrderIsActiveIsDiscarded() {
        String vehicle = newId("v");
        detect(vehicle, MaintenanceReason.BATTERY_LOW, 14, DETECTED);

        detect(vehicle, MaintenanceReason.FAULT, null, DETECTED.plusSeconds(30));

        assertThat(onlyOrderOf(vehicle)).containsEntry("reason", "BATTERY_LOW");
    }

    @Test
    void aDetectionWithoutBatteryLevelIsAccepted() {
        String vehicle = newId("v");

        detect(vehicle, MaintenanceReason.USAGE, null, DETECTED);

        assertThat(onlyOrderOf(vehicle)).containsEntry("reason", "USAGE").containsEntry("batteryLevelPercent", null);
    }

    @Test
    void aLateDetectionOlderThanTheCompletedOrderIsDiscardedButANewOneOpensAnotherOrder() {
        String vehicle = newId("v");
        String orderId = openAndComplete(vehicle, "operario-1");
        String completedAt = (String) getOrder(orderId, Role.ADMIN).getBody().get("completedAt");

        // Llega tarde un VehicleBatteryLow anterior a la vuelta al servicio: no abre una orden.
        detect(vehicle, MaintenanceReason.BATTERY_LOW, 12, DETECTED);
        assertThat(ordersOf(vehicle)).hasSize(1);

        // Una detección posterior sí es una avería nueva.
        detect(vehicle, MaintenanceReason.FAULT, null, Instant.parse(completedAt).plusSeconds(60));
        assertThat(ordersOf(vehicle)).extracting(order -> order.get("status")).containsExactly("COMPLETED", "OPEN");
    }

    // ---- Retirar ----

    @Test
    void withdrawingPutsTheVehicleInWithdrawnAndPublishesTheEventOnce() {
        String vehicle = newId("v");
        String orderId = open(vehicle);
        availabilityAnswers(new StateOutcome.Changed("zone-norte"));

        ResponseEntity<Map> response = post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "WITHDRAWN")
                .containsEntry("operatorId", "operario-1")
                .containsEntry("vehicleId", vehicle);
        verify(availability).changeState(eq(vehicle), eq("WITHDRAWN"), eq("Orden de mantenimiento " + orderId));
        Map<String, Object> event = eventOf("VehicleWithdrawnForMaintenance", orderId);
        assertThat(event).containsEntry("source", "maintenance-orders-service");
        assertThat(eventCount("VehicleWithdrawnForMaintenance", orderId)).isEqualTo(1);
    }

    @Test
    void repeatingTheWithdrawByTheSameOperatorAnswersOkWithoutAnotherEvent() {
        String orderId = open(newId("v"));
        availabilityAnswers(new StateOutcome.Changed("zone-norte"));
        post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null);

        ResponseEntity<Map> again = post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventCount("VehicleWithdrawnForMaintenance", orderId)).isEqualTo(1);
    }

    @Test
    void anotherOperatorCannotTakeAnOrderAlreadyWithdrawn() {
        String orderId = open(newId("v"));
        availabilityAnswers(new StateOutcome.Changed("zone-norte"));
        post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null);

        ResponseEntity<Map> other = post(orderId, "withdraw", "operario-2", Role.MAINTENANCE, null);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getOrder(orderId, Role.ADMIN).getBody()).containsEntry("operatorId", "operario-1");
    }

    @Test
    void ifAvailabilityRejectsTheTransitionTheOrderStaysOpenAndNothingIsPublished() {
        String orderId = open(newId("v"));
        availabilityAnswers(new StateOutcome.Rejected("El vehículo no está en LOW_BATTERY"));

        ResponseEntity<Map> response = post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) response.getBody().get("message")).contains("no está en LOW_BATTERY");
        assertThat(getOrder(orderId, Role.ADMIN).getBody()).containsEntry("status", "OPEN");
        assertThat(eventCount("VehicleWithdrawnForMaintenance", orderId)).isZero();
    }

    @Test
    void ifAvailabilityIsDownTheAnswerIs503TheOrderStaysOpenAndTheOperatorCanRetry() {
        String orderId = open(newId("v"));
        availabilityAnswers(new StateOutcome.Unreachable());

        ResponseEntity<Map> down = post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null);

        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(getOrder(orderId, Role.ADMIN).getBody()).containsEntry("status", "OPEN");
        assertThat(eventCount("VehicleWithdrawnForMaintenance", orderId)).isZero();

        availabilityAnswers(new StateOutcome.Changed("zone-norte"));
        assertThat(post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(eventCount("VehicleWithdrawnForMaintenance", orderId)).isEqualTo(1);
    }

    @Test
    void anUnknownVehicleInAvailabilityIsAConflictAndTheOrderStaysOpen() {
        String orderId = open(newId("v"));
        availabilityAnswers(new StateOutcome.NotFound());

        assertThat(post(orderId, "withdraw", "operario-1", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(getOrder(orderId, Role.ADMIN).getBody()).containsEntry("status", "OPEN");
    }

    // ---- Registrar la tarea ----

    @Test
    void startingARechargeAsksForChargingAndNeedsNoEvent() {
        String vehicle = newId("v");
        String orderId = openAndWithdraw(vehicle, "operario-1");
        int eventsBefore = totalEvents(orderId);

        ResponseEntity<Map> response = post(orderId, "start", "operario-1", Role.MAINTENANCE,
                Map.of("task", "RECHARGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "IN_PROGRESS").containsEntry("task", "RECHARGE");
        verify(availability).changeState(eq(vehicle), eq("CHARGING"), anyString());
        assertThat(totalEvents(orderId)).isEqualTo(eventsBefore);
    }

    @Test
    void aReviewUsesTheRepairingState() {
        String vehicle = newId("v");
        String orderId = openAndWithdraw(vehicle, "operario-1");

        post(orderId, "start", "operario-1", Role.MAINTENANCE, Map.of("task", "REVIEW"));

        verify(availability).changeState(eq(vehicle), eq("REPAIRING"), anyString());
        assertThat(getOrder(orderId, Role.ADMIN).getBody()).containsEntry("task", "REVIEW");
    }

    @Test
    void startingAnOrderThatIsStillOpenIsAConflictAndDoesNotCallAvailability() {
        String orderId = open(newId("v"));

        ResponseEntity<Map> response = post(orderId, "start", "operario-1", Role.MAINTENANCE,
                Map.of("task", "RECHARGE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(availability, never()).changeState(anyString(), anyString(), anyString());
    }

    @Test
    void aMissingOrUnknownTaskIsABadRequest() {
        String orderId = openAndWithdraw(newId("v"), "operario-1");

        assertThat(post(orderId, "start", "operario-1", Role.MAINTENANCE, Map.of("task", "DANCE")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(orderId, "start", "operario-1", Role.MAINTENANCE, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(orderId, "start", "operario-1", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anotherOperatorCannotStartTheTask() {
        String orderId = openAndWithdraw(newId("v"), "operario-1");

        assertThat(post(orderId, "start", "operario-2", Role.MAINTENANCE, Map.of("task", "RECHARGE"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void repeatingTheSameTaskIsOkButAnotherTaskIsAConflict() {
        String orderId = openAndWithdraw(newId("v"), "operario-1");
        post(orderId, "start", "operario-1", Role.MAINTENANCE, Map.of("task", "RECHARGE"));

        assertThat(post(orderId, "start", "operario-1", Role.MAINTENANCE, Map.of("task", "RECHARGE"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(orderId, "start", "operario-1", Role.MAINTENANCE, Map.of("task", "REPAIR"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- Volver al servicio ----

    @Test
    void completingReturnsTheVehicleToServiceInItsZoneAndPublishesTheEventOnce() {
        String vehicle = newId("v");
        String orderId = openWithdrawAndStart(vehicle, "operario-1");
        when(availability.changeState(eq(vehicle), eq("READY_FOR_SERVICE"), anyString()))
                .thenReturn(new StateOutcome.Changed("zone-norte"));
        when(availability.changeState(eq(vehicle), eq("AVAILABLE"), anyString()))
                .thenReturn(new StateOutcome.Changed("zone-norte"));

        ResponseEntity<Map> response = post(orderId, "complete", "operario-1", Role.MAINTENANCE, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "COMPLETED").containsEntry("zoneId", "zone-norte");
        Map<String, Object> event = eventOf("VehicleReturnedToService", orderId);
        assertThat(event).containsEntry("zone", "zone-norte").containsEntry("vehicle", vehicle);

        // Repetir la petición responde lo mismo, con la zona guardada, y no emite otro evento.
        ResponseEntity<Map> again = post(orderId, "complete", "operario-1", Role.MAINTENANCE, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("zoneId", "zone-norte");
        assertThat(eventCount("VehicleReturnedToService", orderId)).isEqualTo(1);
    }

    @Test
    void ifAvailabilityFailsWhileCompletingTheOrderStaysInProgressAndCanBeRepeated() {
        String vehicle = newId("v");
        String orderId = openWithdrawAndStart(vehicle, "operario-1");
        when(availability.changeState(eq(vehicle), eq("READY_FOR_SERVICE"), anyString()))
                .thenReturn(new StateOutcome.Changed("zone-norte"));
        when(availability.changeState(eq(vehicle), eq("AVAILABLE"), anyString()))
                .thenReturn(new StateOutcome.Unreachable());

        ResponseEntity<Map> down = post(orderId, "complete", "operario-1", Role.MAINTENANCE, null);

        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(getOrder(orderId, Role.ADMIN).getBody()).containsEntry("status", "IN_PROGRESS");
        assertThat(eventCount("VehicleReturnedToService", orderId)).isZero();

        when(availability.changeState(eq(vehicle), eq("AVAILABLE"), anyString()))
                .thenReturn(new StateOutcome.Changed("zone-norte"));
        assertThat(post(orderId, "complete", "operario-1", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(eventCount("VehicleReturnedToService", orderId)).isEqualTo(1);
    }

    @Test
    void completingAnOrderThatWasNotStartedIsAConflictAndAnotherOperatorGetsForbidden() {
        String orderId = openAndWithdraw(newId("v"), "operario-1");

        assertThat(post(orderId, "complete", "operario-1", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post(orderId, "complete", "operario-2", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- Consultas, roles y errores ----

    @Test
    void listFiltersByStatusAndVehicleAndRejectsAnUnknownStatus() {
        String vehicle = newId("v");
        String orderId = open(vehicle);
        open(newId("v"));

        List<Map> openOfVehicle = (List<Map>) rest.exchange(BASE + "?status=OPEN&vehicleId=" + vehicle,
                HttpMethod.GET, as("m-1", Role.MAINTENANCE, null), List.class).getBody();
        List<Map> completedOfVehicle = (List<Map>) rest.exchange(BASE + "?status=COMPLETED&vehicleId=" + vehicle,
                HttpMethod.GET, as("m-1", Role.MAINTENANCE, null), List.class).getBody();

        assertThat(openOfVehicle).extracting(order -> order.get("orderId")).containsExactly(orderId);
        assertThat(completedOfVehicle).isEmpty();
        assertThat(rest.exchange(BASE + "?status=PENDING", HttpMethod.GET, as("m-1", Role.MAINTENANCE, null),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listIsOrderedFromTheOldestOrder() {
        String vehicle = newId("v");
        String first = open(vehicle);
        // Un segundo recurso: la lista general contiene las órdenes de otros tests, así que se filtra por estado
        // y se comprueba el orden relativo de las dos.
        String second = open(newId("v"));

        List<Map> all = (List<Map>) rest.exchange(BASE + "?status=OPEN", HttpMethod.GET,
                as("m-1", Role.MAINTENANCE, null), List.class).getBody();

        List<Object> ids = all.stream().map(order -> order.get("orderId")).toList();
        assertThat(ids.indexOf(first)).isLessThan(ids.indexOf(second));
    }

    @Test
    void anAdminCanReadButCannotOperateAndAUserCannotDoAnything() {
        String orderId = open(newId("v"));

        assertThat(getOrder(orderId, Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(orderId, "withdraw", "a-1", Role.ADMIN, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getOrder(orderId, Role.USER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(orderId, "withdraw", "u-1", Role.USER, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withoutTokenIsUnauthorized() {
        assertThat(rest.getForEntity(BASE, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownOrderIsNotFoundWhateverItsFormat() {
        assertThat(getOrder("mo-999999", Role.MAINTENANCE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getOrder("xx-1", Role.MAINTENANCE).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("mo-999999", "withdraw", "m-1", Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- Ayudas ----

    private void detect(String vehicle, MaintenanceReason reason, Integer battery, Instant detectedAt) {
        VehicleBatteryLow payload = new VehicleBatteryLow(vehicle, reason, battery, detectedAt);
        EventType type = EventType.of(VehicleBatteryLow.class);
        batteryLow.handle(payload, new EventEnvelope(UUID.randomUUID(), detectedAt, "test-service", type.wireName(),
                type.version(), "c-test", EventJson.mapper().valueToTree(payload)));
    }

    /** Abre una orden con una detección y devuelve su orderId. */
    private String open(String vehicle) {
        detect(vehicle, MaintenanceReason.BATTERY_LOW, 14, DETECTED);
        return (String) onlyOrderOf(vehicle).get("orderId");
    }

    private String openAndWithdraw(String vehicle, String operator) {
        String orderId = open(vehicle);
        availabilityAnswers(new StateOutcome.Changed("zone-norte"));
        assertThat(post(orderId, "withdraw", operator, Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return orderId;
    }

    private String openWithdrawAndStart(String vehicle, String operator) {
        String orderId = openAndWithdraw(vehicle, operator);
        assertThat(post(orderId, "start", operator, Role.MAINTENANCE, Map.of("task", "RECHARGE")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return orderId;
    }

    private String openAndComplete(String vehicle, String operator) {
        String orderId = openWithdrawAndStart(vehicle, operator);
        availabilityAnswers(new StateOutcome.Changed("zone-norte"));
        assertThat(post(orderId, "complete", operator, Role.MAINTENANCE, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return orderId;
    }

    private void availabilityAnswers(StateOutcome outcome) {
        when(availability.changeState(anyString(), anyString(), anyString())).thenReturn(outcome);
    }

    private List<Map> ordersOf(String vehicle) {
        return (List<Map>) rest.exchange(BASE + "?vehicleId=" + vehicle, HttpMethod.GET,
                as("m-1", Role.MAINTENANCE, null), List.class).getBody();
    }

    private Map onlyOrderOf(String vehicle) {
        List<Map> orders = ordersOf(vehicle);
        assertThat(orders).hasSize(1);
        return orders.get(0);
    }

    private ResponseEntity<Map> getOrder(String orderId, Role role) {
        return rest.exchange(BASE + "/" + orderId, HttpMethod.GET, as("m-1", role, null), Map.class);
    }

    private ResponseEntity<Map> post(String orderId, String action, String userId, Role role, Object body) {
        return rest.exchange(BASE + "/" + orderId + "/" + action, HttpMethod.POST, as(userId, role, body),
                Map.class);
    }

    private HttpEntity<Object> as(String userId, Role role, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(userId, role));
        return new HttpEntity<>(body, headers);
    }

    private int eventCount(String type, String orderId) {
        return jdbc.queryForObject("""
                        SELECT count(*) FROM outbox_events
                         WHERE event_type = ? AND body -> 'payload' ->> 'orderId' = ?
                        """,
                Integer.class, type, orderId);
    }

    private int totalEvents(String orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE body -> 'payload' ->> 'orderId' = ?",
                Integer.class, orderId);
    }

    private Map<String, Object> eventOf(String type, String orderId) {
        return jdbc.queryForMap("""
                        SELECT body ->> 'source' AS source,
                               body -> 'payload' ->> 'vehicleId' AS vehicle,
                               body -> 'payload' ->> 'zoneId' AS zone
                          FROM outbox_events
                         WHERE event_type = ? AND body -> 'payload' ->> 'orderId' = ?
                        """,
                type, orderId);
    }

    /** Ids distintos en cada test: la base se comparte entre tests y no se vacía. */
    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
