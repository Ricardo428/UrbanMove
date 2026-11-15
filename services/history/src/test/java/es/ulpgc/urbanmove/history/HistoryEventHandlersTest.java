package es.ulpgc.urbanmove.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.EventType;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.messaging.EventHandler;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
    private JdbcTemplate jdbc;

    @Test
    void vehicleReservedKeepsTheIdsInColumnsAndTheRestInDetails() {
        VehicleReserved payload = new VehicleReserved("res-1", "v-1", "u-1", "zone-centro", WHEN.plusSeconds(300));
        EventEnvelope event = envelope(payload, WHEN);

        vehicleReserved.handle(payload, event);

        Map<String, Object> row = row(event);
        assertThat(row).containsEntry("event_type", "VehicleReserved")
                .containsEntry("user_id", "u-1")
                .containsEntry("vehicle_id", "v-1")
                .containsEntry("reservation_id", "res-1")
                .containsEntry("ride_id", null);
        assertThat(row.get("details").toString()).contains("zone-centro").doesNotContain("res-1", "u-1");
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
        RideStarted payload = new RideStarted("ride-4", "v-4", "u-4", null, "zone-centro", WHEN);
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
    void theSameEventTwiceIsRejectedByThePrimaryKey() {
        // Red de seguridad: si llegara hasta aquí un duplicado, falla (y SQS lo reintentaría) en vez de duplicar.
        VehicleReserved payload = new VehicleReserved("res-6", "v-6", "u-6", "zone-centro", WHEN);
        EventEnvelope event = envelope(payload, WHEN);
        vehicleReserved.handle(payload, event);

        assertThatThrownBy(() -> vehicleReserved.handle(payload, event)).isInstanceOf(DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM history_entries WHERE event_id = ?", Integer.class,
                event.eventId())).isEqualTo(1);
    }

    private static EventEnvelope envelope(EventPayload payload, Instant timestamp) {
        EventType type = EventType.of(payload.getClass());
        return new EventEnvelope(UUID.randomUUID(), timestamp, "test-service", type.wireName(), type.version(),
                "c-test", EventJson.mapper().valueToTree(payload));
    }

    private Map<String, Object> row(EventEnvelope event) {
        return jdbc.queryForMap("SELECT * FROM history_entries WHERE event_id = ?", event.eventId());
    }
}
