package es.ulpgc.urbanmove.common.error;

/**
 * Formato de error común a todos los servicios (ver los contratos en docs/apis).
 *
 * @param error         nombre del estado HTTP, por ejemplo CONFLICT o BAD_REQUEST
 * @param message       explicación para quien consume la API
 * @param correlationId valor de X-Correlation-Id de la petición
 */
public record ErrorResponse(String error, String message, String correlationId) {
}
