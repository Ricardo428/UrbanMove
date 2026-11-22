package es.ulpgc.urbanmove.relocation.availability;

/** Lo que puede pasar al pedir a availability el estado de un recurso (GET /availability/{vehicleId}). */
public sealed interface VehicleOutcome {

    /** El recurso existe. vehicleType y status son los nombres que usa availability (por ejemplo SCOOTER, AVAILABLE). */
    record Found(String vehicleId, String vehicleType, String zoneId, String status) implements VehicleOutcome {
    }

    /** Availability no conoce el recurso (404). */
    record NotFound() implements VehicleOutcome {
    }

    /** Availability no respondió a tiempo o falló: no se sabe cómo está el recurso. */
    record Unreachable() implements VehicleOutcome {
    }
}
