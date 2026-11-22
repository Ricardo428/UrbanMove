package es.ulpgc.urbanmove.maintenance.availability;

/** Lo que puede pasar al pedir a availability un cambio de estado (PUT /state). */
public sealed interface StateOutcome {

    /** El recurso está en el estado pedido (200, también si ya lo estaba). zoneId es la zona en la que está. */
    record Changed(String zoneId) implements StateOutcome {
    }

    /** Availability no conoce el recurso (404). */
    record NotFound() implements StateOutcome {
    }

    /** La transición no está permitida desde el estado actual del recurso (409). message es el de availability. */
    record Rejected(String message) implements StateOutcome {
    }

    /** Availability no respondió a tiempo o falló: no se sabe si el recurso cambió de estado. */
    record Unreachable() implements StateOutcome {
    }
}
