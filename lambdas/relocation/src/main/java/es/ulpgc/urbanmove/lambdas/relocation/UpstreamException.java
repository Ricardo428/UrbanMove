package es.ulpgc.urbanmove.lambdas.relocation;

/**
 * Analytics o Availability no respondió, o respondió algo distinto de 200. La ejecución no publica nada y termina
 * con error: la de 5 minutos después vuelve a calcularlo todo desde cero (ADR-012).
 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
