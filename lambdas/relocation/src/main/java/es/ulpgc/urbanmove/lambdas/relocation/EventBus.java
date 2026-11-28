package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.EventEnvelope;

/** Dónde se publican los eventos. En producción, el tópico SNS (SnsEventBus); en los tests, una lista. */
@FunctionalInterface
public interface EventBus {

    /** Lanza una RuntimeException si no se pudo publicar. */
    void publish(EventEnvelope event);
}
