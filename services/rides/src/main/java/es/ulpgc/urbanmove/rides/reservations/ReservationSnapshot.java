package es.ulpgc.urbanmove.rides.reservations;

/**
 * Lo que rides necesita saber de una reserva para iniciar un viaje con ella. Es una copia de un momento:
 * la dueña de la reserva es reservations.
 */
public record ReservationSnapshot(String userId, String vehicleId, String status) {

    public boolean belongsTo(String otherUserId) {
        return userId.equals(otherUserId);
    }

    public boolean isFor(String otherVehicleId) {
        return vehicleId.equals(otherVehicleId);
    }

    /** Solo una reserva PENDING o CONFIRMED se puede usar para iniciar un viaje. */
    public boolean isActive() {
        return "PENDING".equals(status) || "CONFIRMED".equals(status);
    }
}
