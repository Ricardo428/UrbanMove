package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.MaintenanceReason;
import java.time.Instant;

/** Recurso retirado de la vía pública por una orden de mantenimiento. */
public record VehicleWithdrawnForMaintenance(
        String vehicleId,
        String orderId,
        MaintenanceReason reason,
        Instant withdrawnAt) implements EventPayload {
}
