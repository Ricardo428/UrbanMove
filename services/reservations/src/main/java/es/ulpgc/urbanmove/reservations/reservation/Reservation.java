package es.ulpgc.urbanmove.reservations.reservation;

import java.time.Instant;
import java.util.OptionalLong;

/** Una fila de reservations. El identificador público es "res-" + id, como en el contrato. */
public record Reservation(
        long id,
        String vehicleId,
        String userId,
        String zoneId,
        ReservationStatus status,
        Instant createdAt,
        Instant expiresAt) {

    private static final String PREFIX = "res-";

    public String publicId() {
        return publicIdOf(id);
    }

    public static String publicIdOf(long id) {
        return PREFIX + id;
    }

    /** Convierte "res-7001" en 7001. Vacío si el texto no tiene ese formato. */
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

    public boolean isOverdue(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
