package es.ulpgc.urbanmove.history.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import es.ulpgc.urbanmove.events.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una entrada del historial: un evento consumido (docs/apis/api-history.md). Los ids van en sus columnas para
 * poder buscar por usuario o vehículo; el resto del payload va en details tal como llegó.
 */
public record HistoryEntry(
        UUID eventId,
        String type,
        Instant occurredAt,
        String userId,
        String vehicleId,
        String reservationId,
        String rideId,
        JsonNode details) {

    /** Campos del payload que ya tienen columna propia y no se repiten en details. */
    private static final List<String> ID_FIELDS = List.of("userId", "vehicleId", "reservationId", "rideId");

    /**
     * occurredAt es el timestamp del evento (cuándo pasó), no el momento en que llega: el bus no garantiza el
     * orden, así que ordenar por llegada mezclaría el historial.
     */
    public static HistoryEntry from(EventEnvelope event, String userId, String vehicleId, String reservationId,
            String rideId) {
        ObjectNode details = event.payload().deepCopy();
        details.remove(ID_FIELDS);
        return new HistoryEntry(event.eventId(), event.type(), event.timestamp(), userId, vehicleId, reservationId,
                rideId, details);
    }
}
