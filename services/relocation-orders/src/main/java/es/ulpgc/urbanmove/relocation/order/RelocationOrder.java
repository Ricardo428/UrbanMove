package es.ulpgc.urbanmove.relocation.order;

import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;

/**
 * Una orden de recolocación tal como está en la base de datos. operatorId y pickedUpAt son null mientras la orden
 * está OPEN (y si se cancela, porque solo se cancela desde OPEN); completedAt, hasta que se completa.
 */
public record RelocationOrder(
        long id,
        String fromZoneId,
        String toZoneId,
        VehicleType vehicleType,
        int quantity,
        OrderStatus status,
        String operatorId,
        Instant createdAt,
        Instant pickedUpAt,
        Instant completedAt) {

    private static final String PREFIX = "ro-";

    /** Identificador que ven la API y los eventos: "ro-" + id. */
    public String publicId() {
        return publicId(id);
    }

    public static String publicId(long id) {
        return PREFIX + id;
    }

    /** Lee "ro-210" y devuelve 210; null si no tiene ese formato. */
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
