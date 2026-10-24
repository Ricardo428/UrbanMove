package es.ulpgc.urbanmove.common.security;

/** El token está mal formado, caducado o firmado con otra clave. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidTokenException(String message) {
        super(message);
    }
}
