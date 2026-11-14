package es.ulpgc.urbanmove.lambdas.expiration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.SecretKey;

/**
 * Lógica de la Lambda de expiración, sin nada de AWS para poder probarla con un servidor HTTP local.
 *
 * <ol>
 *   <li>Firma un token de servicio {@code svc-expiration} con rol ADMIN (ADR-006).</li>
 *   <li>Pide a reservations las reservas vencidas: {@code GET /api/v1/reservations/overdue}.</li>
 *   <li>Expira cada una: {@code POST /api/v1/reservations/{id}/expire}. Reservations guarda el cambio y
 *       publica {@code ReservationExpired} en la misma transacción, así que la Lambda no toca el bus.</li>
 * </ol>
 *
 * Nunca lee la base de datos de reservations: es suya (docs/data-ownership.md).
 */
public final class ExpirationJob {

    static final String SERVICE_USER = "svc-expiration";
    static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    /**
     * Tiempo que la Lambda dedica a expirar antes de dejar el resto para la ejecución del minuto siguiente.
     * Más el peor caso de la llamada en curso (REQUEST_TIMEOUT) y la consulta inicial (otra REQUEST_TIMEOUT)
     * queda en unos 50 s, por debajo del timeout de 60 s de la función en Terraform.
     */
    static final Duration DEFAULT_BUDGET = Duration.ofSeconds(40);
    /**
     * Dos veces el timeout de la Lambda: el token nunca caduca a mitad de una ejecución y, si se filtrara
     * desde los logs o el entorno, valdría pocos minutos (es un ADMIN).
     */
    static final Duration TOKEN_TTL = Duration.ofMinutes(2);
    private static final int MIN_SECRET_BYTES = 32;

    private final String baseUrl;
    private final SecretKey key;
    private final HttpClient http;
    private final Clock clock;
    private final Duration budget;
    private final ObjectMapper json = new ObjectMapper();

    public ExpirationJob(String reservationsUrl, String jwtSecret, HttpClient http, Clock clock) {
        this(reservationsUrl, jwtSecret, http, clock, DEFAULT_BUDGET);
    }

    public ExpirationJob(String reservationsUrl, String jwtSecret, HttpClient http, Clock clock, Duration budget) {
        if (reservationsUrl == null || reservationsUrl.isBlank()) {
            throw new IllegalStateException("Falta la variable de entorno RESERVATIONS_URL");
        }
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "URBANMOVE_JWT_SECRET falta o tiene menos de " + MIN_SECRET_BYTES + " caracteres");
        }
        this.baseUrl = reservationsUrl.endsWith("/")
                ? reservationsUrl.substring(0, reservationsUrl.length() - 1)
                : reservationsUrl;
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.http = http;
        this.clock = clock;
        this.budget = budget;
    }

    public static ExpirationJob fromEnvironment() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        return new ExpirationJob(System.getenv("RESERVATIONS_URL"), System.getenv("URBANMOVE_JWT_SECRET"),
                client, Clock.systemUTC());
    }

    /** Resultado de una ejecución. */
    public record Result(int expired, int alreadyHandled, List<String> failed, int skipped) {

        public String summary() {
            return "expiradas=" + expired + ", ya resueltas=" + alreadyHandled + ", con error=" + failed.size()
                    + ", aplazadas=" + skipped;
        }
    }

    public Result run(Consumer<String> log) {
        // Un identificador por ejecución: permite seguirla en los logs de la Lambda y de reservations.
        String correlationId = "lambda-expiration-" + UUID.randomUUID();
        String token = serviceToken();

        List<String> overdue = listOverdue(token, correlationId);
        log.accept("[" + correlationId + "] reservas vencidas: " + overdue.size());

        // Si se acaba el presupuesto, las que faltan siguen vencidas y salen en la consulta del minuto siguiente.
        Instant deadline = clock.instant().plus(budget);
        int expired = 0;
        int alreadyHandled = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();
        for (String reservationId : overdue) {
            if (!clock.instant().isBefore(deadline)) {
                skipped++;
                continue;
            }
            try {
                int status = expire(reservationId, token, correlationId);
                if (status == 200) {
                    expired++;
                } else if (status == 409) {
                    // Entre la consulta y esta llamada se canceló o se usó para un viaje: no hay nada que hacer.
                    alreadyHandled++;
                } else {
                    failed.add(reservationId + " (HTTP " + status + ")");
                }
            } catch (IOException e) {
                failed.add(reservationId + " (" + e.getMessage() + ")");
            }
        }
        Result result = new Result(expired, alreadyHandled, List.copyOf(failed), skipped);
        log.accept("[" + correlationId + "] " + result.summary());
        return result;
    }

    private List<String> listOverdue(String token, String correlationId) {
        HttpResponse<String> response = send(
                request("/api/v1/reservations/overdue", token, correlationId).GET().build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "reservations respondió HTTP " + response.statusCode() + " al pedir las reservas vencidas");
        }
        try {
            List<String> ids = new ArrayList<>();
            for (JsonNode id : json.readTree(response.body()).path("reservationIds")) {
                ids.add(id.asText());
            }
            return ids;
        } catch (IOException e) {
            throw new IllegalStateException("Respuesta de reservations no válida: " + e.getMessage(), e);
        }
    }

    private int expire(String reservationId, String token, String correlationId) throws IOException {
        String path = "/api/v1/reservations/" + reservationId + "/expire";
        try {
            return http.send(request(path, token, correlationId).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrumpida", e);
        }
    }

    private HttpRequest.Builder request(String path, String token, String correlationId) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header(CORRELATION_HEADER, correlationId);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo llamar a reservations: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ejecución interrumpida", e);
        }
    }

    /** Mismo formato que JwtTokenService de libs/common: subject = usuario y claim "role". */
    private String serviceToken() {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(SERVICE_USER)
                .claim("role", "ADMIN")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
