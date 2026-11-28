package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;

/**
 * Inicio de un viaje. reservationId es null si el viaje empieza sin reserva.
 * vehicleType es null en los eventos anteriores a que se añadiera el campo.
 */
public record RideStarted(
        String rideId,
        String vehicleId,
        VehicleType vehicleType,
        String userId,
        String reservationId,
        String originZoneId,
        Instant startTime) implements EventPayload {
}
