package es.ulpgc.urbanmove.relocation.availability;

/** Lo que puede pasar al pedir a availability un cambio de estado (PUT /availability/{vehicleId}/state). */
public sealed interface StateOutcome {

    /** El recurso está en el estado pedido (200, también si ya lo estaba: el PUT es idempotente). */
    record Changed(String zoneId) implements StateOutcome {
    }

    /** Availability no conoce el recurso (404). */
    record NotFound() implements StateOutcome {
    }

    /** La transición no está permitida desde el estado actual (409). message es el de availability. */
    record Rejected(String message) implements StateOutcome {
    }

    /**
     * Availability no respondió a tiempo o falló: no se sabe si el cambio se aplicó. Por eso, en /pickup, la fila
     * reclamada en relocation_order_vehicles no se borra: al repetir la petición, el mismo PUT lo confirma.
     */
    record Unreachable() implements StateOutcome {
    }
}
