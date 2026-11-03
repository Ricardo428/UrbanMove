package es.ulpgc.urbanmove.rides.reservations;

/** Lo que puede pasar al consultar una reserva en reservations. */
public sealed interface ReservationLookup {

    record Found(ReservationSnapshot reservation) implements ReservationLookup {
    }

    /** Reservations no conoce la reserva (404). */
    record NotFound() implements ReservationLookup {
    }

    /** Reservations no respondió a tiempo o falló. */
    record Unreachable() implements ReservationLookup {
    }
}
