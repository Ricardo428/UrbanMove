package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;

/**
 * Reserva creada sobre un recurso que Availability ya ha pasado a RESERVED.
 * vehicleType es null en los eventos anteriores a que se añadiera el campo.
 */
public record VehicleReserved(
        String reservationId,
        String vehicleId,
        VehicleType vehicleType,
        String userId,
        String zoneId,
        Instant expiresAt) implements EventPayload {
}
