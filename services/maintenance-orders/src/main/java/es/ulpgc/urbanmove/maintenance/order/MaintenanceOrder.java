package es.ulpgc.urbanmove.maintenance.order;

import es.ulpgc.urbanmove.events.MaintenanceReason;
import java.time.Instant;

/**
 * Una orden de mantenimiento tal como está en la base de datos. batteryLevelPercent, task, operatorId, zoneId y
 * las marcas de tiempo de cada paso son null hasta que la orden llega a ese paso.
 */
public record MaintenanceOrder(
        long id,
        String vehicleId,
        MaintenanceReason reason,
        Integer batteryLevelPercent,
        OrderStatus status,
        MaintenanceTask task,
        String operatorId,
        String zoneId,
        Instant detectedAt,
        Instant createdAt,
        Instant withdrawnAt,
        Instant startedAt,
        Instant completedAt) {

    private static final String PREFIX = "mo-";

    /** Identificador que ven la API y los eventos: "mo-" + id. */
    public String publicId() {
        return PREFIX + id;
    }

    /** Lee "mo-310" y devuelve 310; null si no tiene ese formato. */
    public static Long parseId(String publicId) {
        if (publicId == null || !publicId.startsWith(PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(publicId.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
