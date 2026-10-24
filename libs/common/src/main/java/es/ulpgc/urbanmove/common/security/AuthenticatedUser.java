package es.ulpgc.urbanmove.common.security;

/** Usuario que firma una petición, sacado de un JWT válido. En un controlador: @AuthenticationPrincipal. */
public record AuthenticatedUser(String userId, Role role) {
}
