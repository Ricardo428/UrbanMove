package es.ulpgc.urbanmove.history.events;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCompleted;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCreated;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.events.payload.VehicleBatteryLow;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.events.payload.VehicleReturnedToService;
import es.ulpgc.urbanmove.events.payload.VehicleWithdrawnForMaintenance;
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

    // Eventos de vehículo: sin usuario, así que salen en /history/vehicles/{id} y no en /history/me.
    // orderId, reason, zoneId y las fechas van a details: ningún endpoint busca por ellos.

    @Bean
    EventHandler<VehicleBatteryLow> vehicleBatteryLowHistory(HistoryRepository history) {
        return handler(VehicleBatteryLow.class, (payload, event) -> history.save(
                HistoryEntry.from(event, null, payload.vehicleId(), null, null)));
    }

    @Bean
    EventHandler<VehicleWithdrawnForMaintenance> vehicleWithdrawnForMaintenanceHistory(HistoryRepository history) {
        return handler(VehicleWithdrawnForMaintenance.class, (payload, event) -> history.save(
                HistoryEntry.from(event, null, payload.vehicleId(), null, null)));
    }

    @Bean
    EventHandler<VehicleReturnedToService> vehicleReturnedToServiceHistory(HistoryRepository history) {
        return handler(VehicleReturnedToService.class, (payload, event) -> history.save(
                HistoryEntry.from(event, null, payload.vehicleId(), null, null)));
    }

    @Bean
    EventHandler<RelocationOrderCreated> relocationOrderCreatedHistory(HistoryRepository history) {
        // Sin usuario ni vehículo: queda como registro, ningún endpoint actual la devuelve.
        return handler(RelocationOrderCreated.class, (payload, event) -> history.save(
                HistoryEntry.from(event, null, null, null, null)));
    }

    @Bean
    EventHandler<RelocationOrderCompleted> relocationOrderCompletedHistory(HistoryRepository history) {
        // Una entrada por vehículo (V2), para que salga en /history/vehicles/{id} de cada uno. Todas van en la
        // transacción del consumidor: si una falla, no queda ninguna. details conserva la lista vehicleIds
        // completa, así se ve con qué otros vehículos se movió.
        return handler(RelocationOrderCompleted.class, (payload, event) -> payload.vehicleIds().forEach(
                vehicleId -> history.save(HistoryEntry.from(event, null, vehicleId, null, null))));
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
