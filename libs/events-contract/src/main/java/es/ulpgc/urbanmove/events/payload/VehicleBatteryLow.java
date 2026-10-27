package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.MaintenanceReason;
import java.time.Instant;

/** Recurso que necesita mantenimiento. batteryLevelPercent puede ser null si el motivo no es la batería. */
public record VehicleBatteryLow(
        String vehicleId,
        MaintenanceReason reason,
        Integer batteryLevelPercent,
        Instant detectedAt) implements EventPayload {
}
