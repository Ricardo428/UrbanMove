package es.ulpgc.urbanmove.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Sobre común de todos los eventos que viajan por el bus (ver docs/events.md).
 * El payload se guarda como JSON para que un consumidor lo lea con su propia clase ({@link #payloadAs}).
 */
public record EventEnvelope(
        UUID eventId,
        Instant timestamp,
        String source,
        String type,
        String version,
        String correlationId,
        JsonNode payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Crea un evento nuevo con id y timestamp propios.
     *
     * @param correlationId id del flujo en curso; si es null (por ejemplo, un proceso programado) se genera uno
     */
    public static EventEnvelope of(String source, EventPayload payload, String correlationId) {
        EventType type = EventType.of(payload.getClass());
        String correlation = correlationId != null && !correlationId.isBlank()
                ? correlationId
                : "c-" + UUID.randomUUID().toString().substring(0, 8);
        return new EventEnvelope(UUID.randomUUID(), Instant.now(), source, type.wireName(), type.version(),
                correlation, EventJson.mapper().valueToTree(payload));
    }

    public <T extends EventPayload> T payloadAs(Class<T> payloadClass) {
        try {
            return EventJson.mapper().treeToValue(payload, payloadClass);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "El payload del evento " + eventId + " no es un " + payloadClass.getSimpleName(), e);
        }
    }
}
