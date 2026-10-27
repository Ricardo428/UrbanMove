package es.ulpgc.urbanmove.messaging;

import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publica eventos a través de la outbox: el evento se escribe en outbox_events dentro de la transacción
 * del cambio de estado, así que se guardan los dos o ninguno. {@link OutboxRelay} lo envía a SNS después.
 *
 * <pre>
 * &#64;Transactional
 * public Reservation create(...) {
 *     Reservation saved = repository.save(...);
 *     events.publish(new VehicleReserved(...));
 *     return saved;
 * }
 * </pre>
 */
public class EventPublisher {

    private final JdbcTemplate jdbc;
    private final String source;

    public EventPublisher(JdbcTemplate jdbc, String source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    /**
     * Guarda el evento en la outbox con el X-Correlation-Id de la petición en curso.
     *
     * @throws IllegalStateException si no hay una transacción activa: sin ella el evento podría
     *                               guardarse aunque el cambio de estado fallara, o al revés
     */
    public EventEnvelope publish(EventPayload payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "EventPublisher.publish necesita una transacción activa (anota el método con @Transactional)");
        }
        EventEnvelope event = EventEnvelope.of(source, payload, CorrelationIdFilter.current());
        jdbc.update("INSERT INTO outbox_events (event_id, event_type, body) VALUES (?, ?, ?::jsonb)",
                event.eventId(), event.type(), EventJson.write(event));
        return event;
    }
}
