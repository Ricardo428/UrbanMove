package es.ulpgc.urbanmove.messaging;

import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.EventType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Lee la cola SQS del servicio y entrega cada evento a su {@link EventHandler}.
 *
 * <ul>
 *   <li>Idempotente: el eventId se guarda en processed_events en la misma transacción que el handler.
 *       Un evento repetido se descarta sin volver a procesarlo.</li>
 *   <li>El mensaje solo se borra de la cola si la transacción termina bien. Si falla, SQS lo vuelve a entregar
 *       cuando vence su visibility timeout y, tras max_receive_count intentos, lo mueve a la DLQ.</li>
 *   <li>Los eventos sin handler en este servicio se borran sin más: cada cola recibe todos los eventos del topic.</li>
 * </ul>
 */
public class EventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final SqsClient sqs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String queueUrl;
    private final MessagingProperties.Consumer settings;
    private final Map<EventType, EventHandler<?>> handlers = new EnumMap<>(EventType.class);

    private Thread worker;
    private volatile boolean running;

    public EventConsumer(SqsClient sqs, JdbcTemplate jdbc, TransactionTemplate transaction,
            MessagingProperties properties, List<EventHandler<?>> handlers) {
        this.sqs = sqs;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.queueUrl = properties.queueUrl();
        this.settings = properties.consumer();
        for (EventHandler<?> handler : handlers) {
            EventType type = EventType.of(handler.payloadType());
            EventHandler<?> previous = this.handlers.put(type, handler);
            if (previous != null) {
                throw new IllegalStateException("Hay dos handlers para " + type.wireName() + ": "
                        + previous.getClass().getName() + " y " + handler.getClass().getName());
            }
        }
    }

    @Override
    public void start() {
        if (handlers.isEmpty()) {
            return;
        }
        if (queueUrl == null || queueUrl.isBlank()) {
            log.warn("Hay handlers para {} pero falta urbanmove.messaging.queue-url: no se consumen eventos",
                    handlers.keySet());
            return;
        }
        running = true;
        worker = new Thread(this::poll, "event-consumer");
        worker.start();
        log.info("Consumiendo {} desde {}", handlers.keySet(), queueUrl);
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                // Como mucho, lo que dure el long polling en curso.
                worker.join(settings.waitTime().toMillis() + 5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll() {
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessage(request -> request
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(settings.maxMessages())
                        .waitTimeSeconds((int) settings.waitTime().toSeconds())
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                        .messages();
                messages.forEach(this::process);
            } catch (RuntimeException e) {
                log.error("No se pudo leer de la cola {}; se reintenta en {}", queueUrl, settings.errorPause(), e);
                pause();
            }
        }
    }

    private void process(Message message) {
        String attempt = message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "?");
        EventEnvelope event;
        try {
            event = EventJson.read(message.body());
        } catch (IllegalArgumentException e) {
            // No se borra: tras los reintentos acabará en la DLQ, donde se puede revisar.
            log.error("Mensaje {} descartado: no es un evento válido (intento {}): {}",
                    message.messageId(), attempt, e.getMessage());
            return;
        }

        EventHandler<?> handler = EventType.fromWireName(event.type()).map(handlers::get).orElse(null);
        if (handler == null) {
            delete(message);
            return;
        }

        MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        try {
            Boolean handled = transaction.execute(status -> {
                if (!markProcessed(event)) {
                    return false;
                }
                dispatch(handler, event);
                return true;
            });
            delete(message);
            if (Boolean.TRUE.equals(handled)) {
                log.info("Evento consumido: {} {}", event.type(), event.eventId());
            } else {
                log.info("Evento duplicado descartado: {} {}", event.type(), event.eventId());
            }
        } catch (RuntimeException e) {
            log.error("Fallo al procesar {} {} (intento {}); SQS lo volverá a entregar",
                    event.type(), event.eventId(), attempt, e);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /** true si es la primera vez que se ve este eventId. Con dos réplicas a la vez, la segunda espera y ve 0. */
    private boolean markProcessed(EventEnvelope event) {
        return jdbc.update("""
                        INSERT INTO processed_events (event_id, event_type) VALUES (?, ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                event.eventId(), event.type()) == 1;
    }

    private static <T extends EventPayload> void dispatch(EventHandler<T> handler, EventEnvelope event) {
        handler.handle(event.payloadAs(handler.payloadType()), event);
    }

    private void delete(Message message) {
        sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
    }

    private void pause() {
        try {
            Thread.sleep(settings.errorPause().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
