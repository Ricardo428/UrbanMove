package es.ulpgc.urbanmove.maintenance.events;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.VehicleBatteryLow;
import es.ulpgc.urbanmove.maintenance.order.MaintenanceOrderService;
import es.ulpgc.urbanmove.messaging.EventHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Eventos que consume maintenance-orders (docs/apis/api-maintenance-orders.md). libs/messaging descarta los
 * eventId repetidos (processed_events) en la misma transacción que la orden.
 */
@Configuration
public class MaintenanceEventHandlers {

    @Bean
    EventHandler<VehicleBatteryLow> vehicleBatteryLowHandler(MaintenanceOrderService orders) {
        return new EventHandler<>() {
            @Override
            public Class<VehicleBatteryLow> payloadType() {
                return VehicleBatteryLow.class;
            }

            @Override
            public void handle(VehicleBatteryLow payload, EventEnvelope event) {
                orders.openFromDetection(payload);
            }
        };
    }
}
