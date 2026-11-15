package es.ulpgc.urbanmove.history.events;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.history.entry.HistoryEntry;
import es.ulpgc.urbanmove.history.entry.HistoryRepository;
import es.ulpgc.urbanmove.messaging.EventHandler;
import java.util.function.BiConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Eventos que consume history (docs/apis/api-history.md). Cada handler solo traduce su payload a una entrada.
 * libs/messaging descarta los eventId repetidos (processed_events) en la misma transacción que el INSERT.
 * Un evento nuevo (mantenimiento, recolocación) es un bean más: los existentes no cambian.
 */
@Configuration
public class HistoryEventHandlers {

    @Bean
    EventHandler<VehicleReserved> vehicleReservedHistory(HistoryRepository history) {
        return handler(VehicleReserved.class, (payload, event) -> history.save(
                HistoryEntry.from(event, payload.userId(), payload.vehicleId(), payload.reservationId(), null)));
    }

    @Bean
    EventHandler<ReservationCancelled> reservationCancelledHistory(HistoryRepository history) {
        return handler(ReservationCancelled.class, (payload, event) -> history.save(
                HistoryEntry.from(event, payload.userId(), payload.vehicleId(), payload.reservationId(), null)));
    }

    @Bean
    EventHandler<ReservationExpired> reservationExpiredHistory(HistoryRepository history) {
        return handler(ReservationExpired.class, (payload, event) -> history.save(
                HistoryEntry.from(event, payload.userId(), payload.vehicleId(), payload.reservationId(), null)));
    }

    @Bean
    EventHandler<RideStarted> rideStartedHistory(HistoryRepository history) {
        // reservationId es null si el viaje empezó sin reserva.
        return handler(RideStarted.class, (payload, event) -> history.save(HistoryEntry.from(
                event, payload.userId(), payload.vehicleId(), payload.reservationId(), payload.rideId())));
    }

    @Bean
    EventHandler<RideFinished> rideFinishedHistory(HistoryRepository history) {
        return handler(RideFinished.class, (payload, event) -> history.save(
                HistoryEntry.from(event, payload.userId(), payload.vehicleId(), null, payload.rideId())));
    }

    private static <T extends EventPayload> EventHandler<T> handler(Class<T> type,
            BiConsumer<T, EventEnvelope> effect) {
        return new EventHandler<>() {
            @Override
            public Class<T> payloadType() {
                return type;
            }

            @Override
            public void handle(T payload, EventEnvelope event) {
                effect.accept(payload, event);
            }
        };
    }
}
