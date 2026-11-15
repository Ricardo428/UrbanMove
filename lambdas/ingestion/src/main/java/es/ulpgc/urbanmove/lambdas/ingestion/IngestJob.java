package es.ulpgc.urbanmove.lambdas.ingestion;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lógica de la Lambda de ingesta, sin nada de AWS para poder probarla con un almacén en memoria.
 *
 * Copia cada evento tal como llegó a la capa raw del Data Lake. Raw es el registro fiel e inmutable de lo que
 * pasó: no se transforma nada aquí (validar y normalizar es trabajo de la capa trusted). Lo único que se
 * comprueba es que el mensaje sea un evento con su sobre completo, porque sin {@code eventId}, fecha y tipo no se
 * puede ni colocar.
 *
 * Un mensaje que falla no detiene a los demás del lote: se devuelve su id para que SQS solo reentregue ese,
 * y tras el número de intentos configurado acabará en la dead-letter queue.
 */
public final class IngestJob {

    /** Dónde se escribe. En producción es S3; en las pruebas, un mapa. */
    public interface RawStore {

        /** Debe ser idempotente: escribir dos veces la misma clave deja el mismo objeto. */
        void put(String key, String json);
    }

    /** Un mensaje de la cola: su id de SQS y su cuerpo (el JSON del evento). */
    public record Message(String id, String body) {
    }

    /** Resultado de un lote: cuántos se guardaron y los ids de los que fallaron. */
    public record Result(int stored, List<String> failedIds) {

        public String summary() {
            return "guardados=" + stored + ", con error=" + failedIds.size();
        }
    }

    private final RawStore store;

    public IngestJob(RawStore store) {
        this.store = store;
    }

    public Result run(List<Message> messages, Consumer<String> log) {
        int stored = 0;
        List<String> failed = new ArrayList<>();
        for (Message message : messages) {
            try {
                EventEnvelope event = EventJson.read(message.body());
                String key = RawKeys.keyFor(event);
                store.put(key, message.body());
                stored++;
                log.accept("[" + event.correlationId() + "] " + event.type() + " " + event.eventId() + " -> " + key);
            } catch (RuntimeException e) {
                // No se registra el cuerpo del mensaje: puede llevar datos de usuarios.
                failed.add(message.id());
                log.accept("Mensaje " + message.id() + " no guardado: " + e.getMessage());
            }
        }
        Result result = new Result(stored, List.copyOf(failed));
        log.accept(result.summary());
        return result;
    }
}
