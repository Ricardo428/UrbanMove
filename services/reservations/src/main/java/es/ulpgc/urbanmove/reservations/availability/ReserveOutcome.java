package es.ulpgc.urbanmove.reservations.availability;

import es.ulpgc.urbanmove.events.VehicleType;

/** Lo que puede pasar al pedir a availability que reserve un vehículo (ADR-002). */
public sealed interface ReserveOutcome {

    /** El vehículo quedó RESERVED para esta reserva. zoneId es la zona en la que está; vehicleType, su tipo. */
    record Reserved(String zoneId, VehicleType vehicleType) implements ReserveOutcome {
    }

    /** Availability no conoce el vehículo (404). */
    record VehicleNotFound() implements ReserveOutcome {
    }

    /** El vehículo no está AVAILABLE o lo tiene otra reserva (409). */
    record NotAvailable() implements ReserveOutcome {
    }

    /** Availability no respondió a tiempo o falló: no se sabe si el vehículo quedó reservado. */
    record Unreachable() implements ReserveOutcome {
    }
}
