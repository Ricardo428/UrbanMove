package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;

/** Orden para mover recursos de una zona con exceso a otra con déficit. */
public record RelocationOrderCreated(
        String orderId,
        String fromZoneId,
        String toZoneId,
        VehicleType vehicleType,
        int quantity,
        Instant createdAt) implements EventPayload {
}
