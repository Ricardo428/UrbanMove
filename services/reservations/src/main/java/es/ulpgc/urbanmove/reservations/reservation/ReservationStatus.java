package es.ulpgc.urbanmove.reservations.reservation;

/** Estados de una reserva (docs/apis/api-reservations.md). PENDING y CONFIRMED son los únicos activos. */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    REJECTED,
    USED;

    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }
}
