package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Reserva cancelada por el usuario. */
public record ReservationCancelled(
        String reservationId,
        String vehicleId,
        String userId,
        Instant cancelledAt) implements EventPayload {
}
