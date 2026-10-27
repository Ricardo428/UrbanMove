package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Recurso que vuelve a estar disponible tras el mantenimiento. */
public record VehicleReturnedToService(
        String vehicleId,
        String orderId,
        String zoneId,
        Instant returnedAt) implements EventPayload {
}
