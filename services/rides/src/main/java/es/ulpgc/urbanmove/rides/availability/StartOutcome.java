package es.ulpgc.urbanmove.rides.availability;

import es.ulpgc.urbanmove.events.VehicleType;

/** Lo que puede pasar al pedir a availability que ponga un vehículo IN_USE (ADR-002). */
public sealed interface StartOutcome {

    /**
     * El vehículo quedó IN_USE para este viaje. zoneId es la zona en la que estaba (el origen del viaje);
     * vehicleType, su tipo.
     */
    record Started(String zoneId, VehicleType vehicleType) implements StartOutcome {
    }

    /** Availability no conoce el vehículo (404). */
    record VehicleNotFound() implements StartOutcome {
    }

    /** El vehículo no está AVAILABLE o lo tiene reservado otra reserva (409). */
    record NotAvailable() implements StartOutcome {
    }

    /** Availability no respondió a tiempo o falló: no se sabe si el vehículo quedó IN_USE. */
    record Unreachable() implements StartOutcome {
    }
}
