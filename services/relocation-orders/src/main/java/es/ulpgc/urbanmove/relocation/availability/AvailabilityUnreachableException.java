package es.ulpgc.urbanmove.relocation.availability;

/**
 * Availability no respondió mientras se consumía un evento. Se lanza para que el consumidor deshaga la transacción
 * (también la marca en processed_events) y no borre el mensaje: SQS lo vuelve a entregar y, tras 3 intentos, va a la
 * DLQ. En una petición de un operario no se usa: allí se responde con un error y reintenta quien llama.
 */
public class AvailabilityUnreachableException extends RuntimeException {

    public AvailabilityUnreachableException(String message) {
        super(message);
    }
}
