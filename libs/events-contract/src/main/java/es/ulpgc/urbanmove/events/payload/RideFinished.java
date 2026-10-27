package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;

/** Fin de un viaje. batteryLevelPercent lo simula rides hasta que exista telemetría. */
public record RideFinished(
        String rideId,
        String vehicleId,
        String userId,
        String originZoneId,
        String destinationZoneId,
        Instant endTime,
        int durationMinutes,
        BigDecimal preliminaryCost,
        int batteryLevelPercent) implements EventPayload {
}
