package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;
import java.util.List;

/** Recolocación terminada: los recursos movidos quedan en toZoneId. */
public record RelocationOrderCompleted(
        String orderId,
        List<String> vehicleIds,
        String toZoneId,
        Instant completedAt) implements EventPayload {
}
