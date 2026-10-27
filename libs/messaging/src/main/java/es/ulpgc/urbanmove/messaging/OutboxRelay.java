package es.ulpgc.urbanmove.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Lee los eventos pendientes de la outbox y los publica en el topic SNS.
 *
 * <ul>
 *   <li>Varias réplicas del mismo servicio pueden ejecutarlo a la vez: FOR UPDATE SKIP LOCKED reparte las filas.</li>
 *   <li>Si SNS falla, el evento se queda en la outbox y se reintenta con espera exponencial (1 s, 2 s, 4 s...
 *       hasta maxBackoff). No se pierde aunque el servicio se reinicie.</li>
 *   <li>Entrega al menos una vez: si el servicio cae entre publicar y marcar la fila, el evento se publicará
 *       de nuevo. Los consumidores descartan el duplicado por eventId.</li>
 * </ul>
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SnsClient sns;
    private final String topicArn;
    private final MessagingProperties.Relay settings;

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transaction, SnsClient sns,
            MessagingProperties properties) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.sns = sns;
        this.topicArn = properties.topicArn();
        this.settings = properties.relay();
    }

    @Override
    public void start() {
        if (topicArn == null || topicArn.isBlank()) {
            log.warn("Sin urbanmove.messaging.topic-arn: los eventos se guardan en la outbox pero no se publican");
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "outbox-relay"));
        long interval = settings.interval().toMillis();
        scheduler.scheduleWithFixedDelay(this::relayPending, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Publicando la outbox en {}", topicArn);
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Publica lotes hasta vaciar lo pendiente. Lo llama el planificador; público para poder forzarlo en pruebas. */
    public void relayPending() {
        try {
            int read;
            do {
                Integer count = transaction.execute(status -> relayBatch());
                read = count == null ? 0 : count;
            } while (read == settings.batchSize() && running);
        } catch (RuntimeException e) {
            // Por ejemplo, la base de datos no responde. Se reintenta en la siguiente pasada.
            log.error("No se pudo leer la outbox", e);
        }
    }

    private int relayBatch() {
        List<PendingEvent> pending = jdbc.query("""
                        SELECT event_id, event_type, body::text AS body, attempts
                          FROM outbox_events
                         WHERE published_at IS NULL AND next_attempt_at <= now()
                         ORDER BY created_at
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """,
                (rs, row) -> new PendingEvent(rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getString("body"), rs.getInt("attempts")),
                settings.batchSize());

        for (PendingEvent event : pending) {
            try {
                sns.publish(request -> request
                        .topicArn(topicArn)
                        .message(event.body())
                        .messageAttributes(Map.of("eventType", MessageAttributeValue.builder()
                                .dataType("String").stringValue(event.type()).build())));
                jdbc.update("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE event_id = ?",
                        event.id());
                log.info("Evento publicado: {} {}", event.type(), event.id());
            } catch (RuntimeException e) {
                int attempts = event.attempts() + 1;
                long backoff = Math.min(1L << Math.min(attempts - 1, 20), settings.maxBackoff().toSeconds());
                jdbc.update("""
                                UPDATE outbox_events
                                   SET attempts = ?, last_error = ?,
                                       next_attempt_at = now() + make_interval(secs => ?)
                                 WHERE event_id = ?
                                """,
                        attempts, truncate(e.toString()), backoff, event.id());
                log.warn("No se pudo publicar {} {} (intento {}), se reintenta en {} s: {}",
                        event.type(), event.id(), attempts, backoff, e.getMessage());
            }
        }
        return pending.size();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    private record PendingEvent(UUID id, String type, String body, int attempts) {
    }
}
