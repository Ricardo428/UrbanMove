package es.ulpgc.urbanmove.lambdas.relocation;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * Token de servicio {@code svc-relocation} con rol ADMIN (ADR-006), con el mismo formato que JwtTokenService de
 * libs/common: subject = usuario y claim "role". Se firma uno nuevo en cada llamada.
 */
public final class ServiceToken {

    static final String SERVICE_USER = "svc-relocation";
    /** Dos veces el timeout de la Lambda: nunca caduca a mitad de una ejecución y, si se filtrara, vale poco. */
    static final Duration TTL = Duration.ofMinutes(2);
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Clock clock;

    public ServiceToken(String jwtSecret, Clock clock) {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "URBANMOVE_JWT_SECRET falta o tiene menos de " + MIN_SECRET_BYTES + " caracteres");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String sign() {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(SERVICE_USER)
                .claim("role", "ADMIN")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TTL)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
