package es.ulpgc.urbanmove.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Formato JSON de los eventos. Productores y consumidores (servicios y Lambdas) usan este mismo mapper:
 * fechas en ISO 8601 y campos desconocidos ignorados, para que añadir un campo no rompa a los consumidores.
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(EventEnvelope event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se puede serializar el evento " + event.eventId(), e);
        }
    }

    /** Lee un evento del bus. Lanza IllegalArgumentException si no es JSON o le falta algún campo del sobre. */
    public static EventEnvelope read(String json) {
        try {
            return MAPPER.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("El mensaje no es un evento válido: " + e.getOriginalMessage(), e);
        }
    }
}
