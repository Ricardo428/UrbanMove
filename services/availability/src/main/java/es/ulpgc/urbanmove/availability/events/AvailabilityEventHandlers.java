package es.ulpgc.urbanmove.availability.events;

import es.ulpgc.urbanmove.availability.vehicle.AvailabilityService;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.VehiclePurchased;
import es.ulpgc.urbanmove.messaging.EventHandler;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Eventos que consume availability (docs/apis/api-availability.md). libs/messaging descarta los eventId repetidos;
 * además cada efecto es un UPDATE condicional, así que un evento fuera de orden o ya aplicado no cambia nada.
 */
@Configuration
public class AvailabilityEventHandlers {

    @Bean
    EventHandler<VehiclePurchased> vehiclePurchasedHandler(AvailabilityService availability) {
        return handler(VehiclePurchased.class,
                event -> availability.register(event.vehicleId(), event.vehicleType(), event.zoneId()));
    }

    @Bean
    EventHandler<ReservationCancelled> reservationCancelledHandler(AvailabilityService availability) {
        return handler(ReservationCancelled.class,
                event -> availability.release(event.vehicleId(), event.reservationId()));
    }

    @Bean
    EventHandler<ReservationExpired> reservationExpiredHandler(AvailabilityService availability) {
        return handler(ReservationExpired.class,
                event -> availability.release(event.vehicleId(), event.reservationId()));
    }

    @Bean
    EventHandler<RideFinished> rideFinishedHandler(AvailabilityService availability) {
        return handler(RideFinished.class, event -> availability.finishRide(event.vehicleId(), event.rideId(),
                event.batteryLevelPercent(), event.destinationZoneId()));
    }

    private static <T extends EventPayload> EventHandler<T> handler(Class<T> type, Consumer<T> effect) {
        return new EventHandler<>() {
            @Override
            public Class<T> payloadType() {
                return type;
            }

            @Override
            public void handle(T payload, EventEnvelope event) {
                effect.accept(payload);
            }
        };
    }
}
