package es.ulpgc.urbanmove.identity.auth;

public record LoginResponse(String token, String role, long expiresIn) {
}
