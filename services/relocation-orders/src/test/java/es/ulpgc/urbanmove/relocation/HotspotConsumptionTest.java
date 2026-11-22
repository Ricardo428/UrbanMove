package es.ulpgc.urbanmove.relocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventType;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import es.ulpgc.urbanmove.messaging.EventHandler;
import es.ulpgc.urbanmove.relocation.availability.AvailabilityUnreachableException;
import es.ulpgc.urbanmove.relocation.availability.CountOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cómo nace una orden al consumir DemandHotspotDetected (docs/apis/api-relocation-orders.md). El handler se llama
 * directamente, como lo haría el EventConsumer, y los eventos se miran en outbox_events. Availability es un doble:
 * cada test le dice cuántos recursos hay en cada zona. Objetivo 6 en destino y mínimo 2 en origen.
 */
class HotspotConsumptionTest extends AbstractIntegrationTest {

    private static final Instant WINDOW_END = Instant.parse("2026-10-13T16:00:00Z");

    @Autowired
    private EventHandler<DemandHotspotDetected> hotspotHandler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void aHotspotCreatesAnOpenOrderFromTheZoneWithMostAvailableAndPublishesItOnce() {
        String centro = newZone();
        String norte = newZone();
        String sur = newZone();
        availabilityCounts(VehicleType.SCOOTER, Map.of(centro, 1, norte, 7, sur, 4));

        detectHotspot(centro, VehicleType.SCOOTER);

        Map<String, Object> order = onlyOrderTo(centro);
        assertThat(order).containsEntry("from_zone_id", norte)
                .containsEntry("vehicle_type", "SCOOTER")
                .containsEntry("quantity", 5)
                .containsEntry("status", "OPEN");
        assertThat(order.get("operator_id")).isNull();

        String orderId = "ro-" + order.get("id");
        List<Map<String, Object>> events = createdEventsTo(centro);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("order_id", orderId)
                .containsEntry("from_zone", norte)
                .containsEntry("quantity", "5")
                .containsEntry("source", "relocation-orders-service");
    }

    @Test
    void aSecondHotspotOfTheSameZoneWhileTheOrderIsActiveCreatesNothing() {
        String centro = newZone();
        availabilityCounts(VehicleType.BIKE, Map.of(centro, 0, newZone(), 9));

        detectHotspot(centro, VehicleType.BIKE);
        detectHotspot(centro, VehicleType.BIKE);

        assertThat(ordersTo(centro)).hasSize(1);
        assertThat(createdEventsTo(centro)).hasSize(1);
    }

    @Test
    void theSameZoneCanHaveAnActiveOrderForEachType() {
        String centro = newZone();
        String norte = newZone();
        availabilityCounts(VehicleType.BIKE, Map.of(norte, 9));
        availabilityCounts(VehicleType.SCOOTER, Map.of(norte, 9));

        detectHotspot(centro, VehicleType.BIKE);
        detectHotspot(centro, VehicleType.SCOOTER);

        assertThat(ordersTo(centro)).extracting(order -> order.get("vehicle_type"))
                .containsExactlyInAnyOrder("BIKE", "SCOOTER");
    }

    @Test
    void aLateHotspotWhoseZoneAlreadyReachesTheTargetCreatesNothing() {
        String centro = newZone();
        availabilityCounts(VehicleType.BIKE, Map.of(centro, 6, newZone(), 9));

        detectHotspot(centro, VehicleType.BIKE);

        assertThat(ordersTo(centro)).isEmpty();
        assertThat(createdEventsTo(centro)).isEmpty();
    }

    @Test
    void ifAvailabilityIsDownTheHandlerFailsAndLeavesNeitherOrderNorEvent() {
        String centro = newZone();
        when(availability.countAvailableByZone(VehicleType.BIKE)).thenReturn(new CountOutcome.Unreachable());

        assertThatThrownBy(() -> detectHotspot(centro, VehicleType.BIKE))
                .isInstanceOf(AvailabilityUnreachableException.class);

        assertThat(ordersTo(centro)).isEmpty();
        assertThat(createdEventsTo(centro)).isEmpty();
    }

    @Test
    void ifTheConsumerTransactionIsRolledBackTheOrderAndTheEventAreUndoneTogether() {
        // Como en el EventConsumer: el handler corre dentro de una transacción que luego falla.
        String centro = newZone();
        availabilityCounts(VehicleType.BIKE, Map.of(newZone(), 9));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            detectHotspot(centro, VehicleType.BIKE);
            throw new IllegalStateException("falla algo después del handler");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ordersTo(centro)).isEmpty();
        assertThat(createdEventsTo(centro)).isEmpty();
    }

    // ---- Ayudas ----

    private void detectHotspot(String zoneId, VehicleType type) {
        DemandHotspotDetected payload = new DemandHotspotDetected(zoneId, type, 1, 12,
                WINDOW_END.minusSeconds(3600), WINDOW_END);
        EventType eventType = EventType.of(DemandHotspotDetected.class);
        hotspotHandler.handle(payload, new EventEnvelope(UUID.randomUUID(), WINDOW_END, "relocation-lambda",
                eventType.wireName(), eventType.version(), "c-test", EventJson.mapper().valueToTree(payload)));
    }

    private void availabilityCounts(VehicleType type, Map<String, Integer> availableByZone) {
        when(availability.countAvailableByZone(type)).thenReturn(new CountOutcome.Counted(availableByZone));
    }

    private List<Map<String, Object>> ordersTo(String zoneId) {
        return jdbc.queryForList("SELECT * FROM relocation_orders WHERE to_zone_id = ?", zoneId);
    }

    private Map<String, Object> onlyOrderTo(String zoneId) {
        List<Map<String, Object>> orders = ordersTo(zoneId);
        assertThat(orders).hasSize(1);
        return orders.get(0);
    }

    private List<Map<String, Object>> createdEventsTo(String zoneId) {
        return jdbc.queryForList("""
                        SELECT body ->> 'source' AS source,
                               body -> 'payload' ->> 'orderId' AS order_id,
                               body -> 'payload' ->> 'fromZoneId' AS from_zone,
                               body -> 'payload' ->> 'quantity' AS quantity
                          FROM outbox_events
                         WHERE event_type = 'RelocationOrderCreated' AND body -> 'payload' ->> 'toZoneId' = ?
                        """,
                zoneId);
    }

    /** Zonas distintas en cada test: la base se comparte entre tests y no se vacía. */
    private static String newZone() {
        return "zone-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
