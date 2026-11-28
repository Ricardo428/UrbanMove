package es.ulpgc.urbanmove.lambdas.relocation;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.Map;

/**
 * Punto de entrada de la Lambda. EventBridge la invoca cada 5 minutos; el evento que llega no se usa.
 *
 * Termina con excepción, para que la invocación conste como fallida, si analytics o availability fallan, si el
 * data mart no está al día o si algún hotspot no se pudo publicar. No se reintenta (maximum_retry_attempts = 0 en
 * Terraform): la ejecución siguiente vuelve a calcularlo todo desde cero (ADR-012).
 */
public class RelocationHandler implements RequestHandler<Map<String, Object>, String> {

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        RelocationJob job = RelocationJob.fromEnvironment(System.getenv());
        RelocationJob.Result result = job.run(message -> context.getLogger().log(message + "\n"));
        if (!result.failed().isEmpty()) {
            throw new IllegalStateException(
                    "No se pudieron publicar " + result.failed().size() + " hotspots: " + result.failed());
        }
        return result.summary();
    }
}
