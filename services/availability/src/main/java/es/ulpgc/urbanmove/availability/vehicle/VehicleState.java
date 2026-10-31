package es.ulpgc.urbanmove.availability.vehicle;

import es.ulpgc.urbanmove.events.VehicleType;

/** Una fila de vehicle_state. */
public record VehicleState(
        String vehicleId,
        VehicleType vehicleType,
        String zoneId,
        VehicleStatus status,
        String reservationId,
        String rideId) {
}
