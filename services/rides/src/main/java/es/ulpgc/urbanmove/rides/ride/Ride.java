package es.ulpgc.urbanmove.rides.ride;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Una fila de rides. El identificador público es "ride-" + id, como en el contrato.
 * Los campos de fin (destinationZoneId ... batteryLevelPercent) son null hasta que el viaje termina.
 */
public record Ride(
        long id,
        String vehicleId,
        String userId,
        String reservationId,
        RideStatus status,
        String originZoneId,
        Instant startTime,
        String destinationZoneId,
        Instant endTime,
        Integer durationMinutes,
        Boolean zoneValidated,
        BigDecimal preliminaryCost,
        Integer batteryLevelPercent) {

    private static final String PREFIX = "ride-";

    public String publicId() {
        return publicIdOf(id);
    }

    public static String publicIdOf(long id) {
        return PREFIX + id;
    }

    /** Convierte "ride-5501" en 5501. Vacío si el texto no tiene ese formato. */
    public static OptionalLong parsePublicId(String publicId) {
        if (publicId == null || !publicId.startsWith(PREFIX)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(publicId.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
