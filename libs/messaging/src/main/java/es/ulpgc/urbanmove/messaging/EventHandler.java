package es.ulpgc.urbanmove.messaging;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventPayload;

/**
 * Procesa un tipo de evento. Basta con declararlo como bean: {@link EventConsumer} lo registra solo.
 *
 * <p>Se ejecuta dentro de una transacción junto con la marca de "evento procesado". Si lanza una excepción,
 * se deshace todo y SQS vuelve a entregar el mensaje; tras varios fallos acaba en la dead-letter queue.
 * Los eventos publicados desde aquí con {@link EventPublisher} heredan el correlationId del evento recibido.
 */
public interface EventHandler<T extends EventPayload> {

    Class<T> payloadType();

    void handle(T payload, EventEnvelope event);
}
