package es.ulpgc.urbanmove.reservations.events;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.messaging.EventHandler;
import es.ulpgc.urbanmove.reservations.reservation.ReservationService;
import org.springframework.stereotype.Component;

/** Un viaje que empieza con reserva la marca como USED, para que la Lambda de expiración no la caduque. */
@Component
public class RideStartedHandler implements EventHandler<RideStarted> {

    private final ReservationService reservations;

    public RideStartedHandler(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Override
    public Class<RideStarted> payloadType() {
        return RideStarted.class;
    }

    @Override
    public void handle(RideStarted payload, EventEnvelope event) {
        if (payload.reservationId() != null) {
            reservations.markUsed(payload.reservationId());
        }
    }
}
