package es.ulpgc.urbanmove.relocation.events;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import es.ulpgc.urbanmove.messaging.EventHandler;
import es.ulpgc.urbanmove.relocation.order.RelocationOrderService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Eventos que consume relocation-orders (docs/apis/api-relocation-orders.md). libs/messaging descarta los eventId
 * repetidos (processed_events) en la misma transacción que la orden. Dos hotspots distintos de la misma zona (dos
 * eventId) los frena el índice único parcial de órdenes activas.
 */
@Configuration
public class RelocationEventHandlers {

    @Bean
    EventHandler<DemandHotspotDetected> demandHotspotHandler(RelocationOrderService orders) {
        return new EventHandler<>() {
            @Override
            public Class<DemandHotspotDetected> payloadType() {
                return DemandHotspotDetected.class;
            }

            @Override
            public void handle(DemandHotspotDetected payload, EventEnvelope event) {
                orders.createFromHotspot(payload);
            }
        };
    }
}
