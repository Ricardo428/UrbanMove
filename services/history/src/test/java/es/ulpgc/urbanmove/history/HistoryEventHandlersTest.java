package es.ulpgc.urbanmove.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.EventType;
import es.ulpgc.urbanmove.events.MaintenanceReason;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCompleted;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCreated;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.events.payload.VehicleBatteryLow;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.events.payload.VehicleReturnedToService;
import es.ulpgc.urbanmove.events.payload.VehicleWithdrawnForMaintenance;
import es.ulpgc.urbanmove.history.entry.HistoryRepository;
import es.ulpgc.urbanmove.messaging.EventHandler;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Los handlers se llaman directamente, sin SQS: que un eventId repetido se descarte (processed_events) ya lo
 * prueba MessagingIntegrationTest en libs/messaging. Aquí se prueba qué fila guarda cada evento.
 */
class HistoryEventHandlersTest extends AbstractIntegrationTest {

    private static final Instant WHEN = Instant.parse("2026-10-13T10:15:00Z");

    @Autowired
    private EventHandler<VehicleReserved> vehicleReserved;

    @Autowired
    private EventHandler<ReservationCancelled> reservationCancelled;

    @Autowired
    private EventHandler<ReservationExpired> reservationExpired;

    @Autowired
    private EventHandler<RideStarted> rideStarted;

    @Autowired
    private EventHandler<RideFinished> rideFinished;

    @Autowired
    private EventHandler<VehicleBatteryLow> vehicleBatteryLow;

    @Autowired
    private EventHandler<VehicleWithdrawnForMaintenance> vehicleWithdrawn;

    @Autowired
    private EventHandler<VehicleReturnedToService> vehicleReturned;

    @Autowired
    private EventHandler<RelocationOrderCreated> relocationOrderCreated;

    @Autowired
    private EventHandler<RelocationOrderCompleted> relocationOrderCompleted;

    @Autowired
    private HistoryRepository history;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void vehicleReservedKeepsTheIdsInColumnsAndTheRestInDetails() {
        VehicleReserved payload = new VehicleReserved("res-1", "v-1", VehicleType.BIKE, "u-1", "zone-centro",
                WHEN.plusSeconds(300));
        EventEnvelope event = envelope(payload, WHEN);

        vehicleReserved.handle(payload, event);

        Map<String, Object> row = row(event);
        assertThat(row).containsEntry("event_type", "VehicleReserved")
                .containsEntry("user_id", "u-1")
                .containsEntry("vehicle_id", "v-1")
                .containsEntry("reservation_id", "res-1")
                .containsEntry("ride_id", null);
        assertThat(row.get("details").toString()).contains("zone-centro", "BIKE").doesNotContain("res-1", "u-1");
    }

    @Test
    void occurredAtIsTheEventTimestampNotTheArrivalTime() {
        // El evento ocurrió hace un mes y llega ahora (por ejemplo, history estuvo caído).
        ReservationCancelled payload = new ReservationCancelled("res-2", "v-2", "u-2", WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        reservationCancelled.handle(payload, event);

        assertThat(row(event).get("occurred_at")).isEqualTo(Timestamp.from(WHEN));
    }

    @Test
    void reservationExpiredIsStoredForTheUserAndTheVehicle() {
        ReservationExpired payload = new ReservationExpired("res-3", "v-3", "u-3", WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        reservationExpired.handle(payload, event);

        assertThat(row(event)).containsEntry("event_type", "ReservationExpired")
                .containsEntry("user_id", "u-3")
                .containsEntry("vehicle_id", "v-3");
    }

    @Test
    void rideStartedWithoutReservationLeavesReservationIdEmpty() {
        RideStarted payload = new RideStarted("ride-4", "v-4", VehicleType.BIKE, "u-4", null, "zone-centro", WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        rideStarted.handle(payload, event);

        assertThat(row(event)).containsEntry("ride_id", "ride-4").containsEntry("reservation_id", null);
    }

    @Test
    void rideFinishedKeepsZonesDurationAndCostInDetails() {
        RideFinished payload = new RideFinished("ride-5", "v-5", "u-5", "zone-centro", "zone-norte", WHEN, 30,
                new BigDecimal("4.5"), 14);
        EventEnvelope event = envelope(payload, WHEN);

        rideFinished.handle(payload, event);

        assertThat(row(event).get("details").toString())
                .contains("zone-norte", "\"durationMinutes\": 30", "4.5");
    }

    @Test
    void vehicleBatteryLowIsStoredForTheVehicleWithoutUser() {
        VehicleBatteryLow payload = new VehicleBatteryLow("v-7", MaintenanceReason.BATTERY_LOW, 12, WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        vehicleBatteryLow.handle(payload, event);

        Map<String, Object> row = row(event);
        assertThat(row).containsEntry("event_type", "VehicleBatteryLow")
                .containsEntry("user_id", null)
                .containsEntry("vehicle_id", "v-7");
        assertThat(row.get("details").toString()).contains("BATTERY_LOW", "\"batteryLevelPercent\": 12");
    }

    @Test
    void vehicleWithdrawnKeepsTheMaintenanceOrderInDetails() {
        VehicleWithdrawnForMaintenance payload =
                new VehicleWithdrawnForMaintenance("v-8", "mo-8", MaintenanceReason.FAULT, WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        vehicleWithdrawn.handle(payload, event);

        Map<String, Object> row = row(event);
        assertThat(row).containsEntry("user_id", null).containsEntry("vehicle_id", "v-8");
        assertThat(row.get("details").toString()).contains("mo-8", "FAULT");
    }

    @Test
    void vehicleReturnedKeepsTheZoneInDetails() {
        VehicleReturnedToService payload = new VehicleReturnedToService("v-9", "mo-9", "zone-norte", WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        vehicleReturned.handle(payload, event);

        Map<String, Object> row = row(event);
        assertThat(row).containsEntry("event_type", "VehicleReturnedToService").containsEntry("vehicle_id", "v-9");
        assertThat(row.get("details").toString()).contains("zone-norte", "mo-9");
    }

    @Test
    void relocationOrderCreatedHasNeitherUserNorVehicle() {
        RelocationOrderCreated payload =
                new RelocationOrderCreated("ro-10", "zone-sur", "zone-centro", VehicleType.BIKE, 3, WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        relocationOrderCreated.handle(payload, event);

        Map<String, Object> row = row(event);
        assertThat(row).containsEntry("user_id", null).containsEntry("vehicle_id", null);
        assertThat(row.get("details").toString()).contains("ro-10", "zone-sur", "zone-centro");
    }

    @Test
    void relocationOrderCompletedStoresOneEntryPerVehicle() {
        RelocationOrderCompleted payload =
                new RelocationOrderCompleted("ro-11", List.of("v-11a", "v-11b", "v-11c"), "zone-centro", WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        relocationOrderCompleted.handle(payload, event);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM history_entries WHERE event_id = ? ORDER BY vehicle_id", event.eventId());
        assertThat(rows).extracting(r -> r.get("vehicle_id")).containsExactly("v-11a", "v-11b", "v-11c");
        // Cada entrada conserva la lista completa: se ve con qué otros vehículos se movió.
        assertThat(rows).allSatisfy(r -> assertThat(r.get("details").toString())
                .contains("ro-11", "v-11a", "v-11b", "v-11c"));
    }

    @Test
    void relocationOrderCompletedShowsUpInEachVehicleHistory() {
        RelocationOrderCompleted payload =
                new RelocationOrderCompleted("ro-12", List.of("v-12a", "v-12b"), "zone-centro", WHEN);
        EventEnvelope event = envelope(payload, WHEN);

        relocationOrderCompleted.handle(payload, event);

        assertThat(history.findByVehicle("v-12b", 50)).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.eventId()).isEqualTo(event.eventId());
                    assertThat(entry.type()).isEqualTo("RelocationOrderCompleted");
                });
    }

    @Test
    void theSameEventWithoutVehicleTwiceIsRejected() {
        // Sin NULLS NOT DISTINCT (V2), dos vehicle_id NULL contarían como distintos y el duplicado se colaría.
        RelocationOrderCreated payload =
                new RelocationOrderCreated("ro-13", "zone-sur", "zone-centro", VehicleType.SCOOTER, 2, WHEN);
        EventEnvelope event = envelope(payload, WHEN);
        relocationOrderCreated.handle(payload, event);

        assertThatThrownBy(() -> relocationOrderCreated.handle(payload, event))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(count(event)).isEqualTo(1);
    }

    @Test
    void theSameEventTwiceIsRejectedByTheUniqueIndex() {
        // Red de seguridad: si llegara hasta aquí un duplicado, falla (y SQS lo reintentaría) en vez de duplicar.
        VehicleReserved payload = new VehicleReserved("res-6", "v-6", VehicleType.BIKE, "u-6", "zone-centro", WHEN);
        EventEnvelope event = envelope(payload, WHEN);
        vehicleReserved.handle(payload, event);

        assertThatThrownBy(() -> vehicleReserved.handle(payload, event)).isInstanceOf(DuplicateKeyException.class);
        assertThat(count(event)).isEqualTo(1);
    }

    private static EventEnvelope envelope(EventPayload payload, Instant timestamp) {
        EventType type = EventType.of(payload.getClass());
        return new EventEnvelope(UUID.randomUUID(), timestamp, "test-service", type.wireName(), type.version(),
                "c-test", EventJson.mapper().valueToTree(payload));
    }

    private Map<String, Object> row(EventEnvelope event) {
        return jdbc.queryForMap("SELECT * FROM history_entries WHERE event_id = ?", event.eventId());
    }

    private int count(EventEnvelope event) {
        return jdbc.queryForObject("SELECT count(*) FROM history_entries WHERE event_id = ?", Integer.class,
                event.eventId());
    }
}
