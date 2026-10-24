package es.ulpgc.urbanmove.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * Emite (solo lo usa identity) y comprueba tokens JWT firmados con HS256.
 * El secreto llega por configuración (variable de entorno URBANMOVE_JWT_SECRET), nunca en el repositorio.
 */
public class JwtTokenService {

    private static final String ROLE_CLAIM = "role";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtTokenService(String secret, long expirationSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Falta la clave de firma del JWT: define la variable de entorno URBANMOVE_JWT_SECRET");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "La clave de firma del JWT debe tener al menos " + MIN_SECRET_BYTES + " caracteres");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationSeconds = expirationSeconds;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public String generate(String userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public AuthenticatedUser parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String userId = claims.getSubject();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (userId == null || role == null) {
                throw new InvalidTokenException("El token no contiene usuario o rol");
            }
            return new AuthenticatedUser(userId, Role.valueOf(role));
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Token expirado o inválido", e);
        }
    }
}
