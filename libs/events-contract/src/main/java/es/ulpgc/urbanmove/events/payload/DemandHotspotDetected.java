package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;

/** Zona con más demanda que recursos disponibles en la ventana indicada. */
public record DemandHotspotDetected(
        String zoneId,
        VehicleType vehicleType,
        int availableCount,
        int requestsLastHour,
        Instant windowStart,
        Instant windowEnd) implements EventPayload {
}
