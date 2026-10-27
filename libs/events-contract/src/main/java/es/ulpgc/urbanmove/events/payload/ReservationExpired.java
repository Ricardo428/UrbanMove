package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Reserva liberada por timeout. */
public record ReservationExpired(
        String reservationId,
        String vehicleId,
        String userId,
        Instant expiredAt) implements EventPayload {
}
