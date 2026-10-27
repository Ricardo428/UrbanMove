package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Inicio de un viaje. reservationId es null si el viaje empieza sin reserva. */
public record RideStarted(
        String rideId,
        String vehicleId,
        String userId,
        String reservationId,
        String originZoneId,
        Instant startTime) implements EventPayload {
}
