package es.ulpgc.urbanmove.lambdas.expiration;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

/**
 * Punto de entrada de la Lambda. EventBridge la invoca cada minuto; el evento que llega no se usa.
 * Si alguna reserva no se pudo expirar, lanza una excepción para que la invocación conste como fallida.
 * No se reintenta (maximum_retry_attempts = 0 en Terraform): la ejecución del minuto siguiente vuelve a
 * encontrar esas reservas. Las que se aplazaron por falta de tiempo no son un error y se retoman igual.
 */
public class ExpirationHandler implements RequestHandler<Map<String, Object>, String> {

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        ExpirationJob job = ExpirationJob.fromEnvironment();
        ExpirationJob.Result result = job.run(message -> context.getLogger().log(message + "\n"));
        if (!result.failed().isEmpty()) {
            throw new IllegalStateException(
                    "No se pudieron expirar " + result.failed().size() + " reservas: " + result.failed());
        }
        return result.summary();
    }
}
