package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Reserva creada sobre un recurso que Availability ya ha pasado a RESERVED. */
public record VehicleReserved(
        String reservationId,
        String vehicleId,
        String userId,
        String zoneId,
        Instant expiresAt) implements EventPayload {
}
