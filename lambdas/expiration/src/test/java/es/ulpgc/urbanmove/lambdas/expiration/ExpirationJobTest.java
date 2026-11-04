package es.ulpgc.urbanmove.lambdas.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Prueba la lógica de la Lambda contra un servidor HTTP local que hace de reservations. */
class ExpirationJobTest {

    private static final String SECRET = "clave-solo-para-tests-de-la-lambda-0123456789";

    private HttpServer reservations;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> expireStatus = new ConcurrentHashMap<>();
    private final Set<String> authorizations = ConcurrentHashMap.newKeySet();
    private final Set<String> correlationIds = ConcurrentHashMap.newKeySet();
    private volatile int overdueStatus = 200;
    private volatile String overdueBody = "{\"reservationIds\":[]}";

    @BeforeEach
    void startReservations() throws IOException {
        reservations = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reservations.createContext("/api/v1/reservations/", this::handle);
        reservations.start();
    }

    @AfterEach
    void stopReservations() {
        reservations.stop(0);
    }

    @Test
    void expiresEveryOverdueReservation() {
        overdueBody = "{\"reservationIds\":[\"res-1\",\"res-2\"]}";

        ExpirationJob.Result result = job().run(message -> { });

        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.alreadyHandled()).isZero();
        assertThat(result.failed()).isEmpty();
        assertThat(requests).containsExactly(
                "GET /api/v1/reservations/overdue",
                "POST /api/v1/reservations/res-1/expire",
                "POST /api/v1/reservations/res-2/expire");
    }

    @Test
    void doesNothingWhenNothingIsOverdue() {
        ExpirationJob.Result result = job().run(message -> { });

        assertThat(result.expired()).isZero();
        assertThat(requests).containsExactly("GET /api/v1/reservations/overdue");
    }

    @Test
    void aConflictMeansSomeoneElseAlreadyHandledTheReservation() {
        overdueBody = "{\"reservationIds\":[\"res-1\",\"res-2\"]}";
        expireStatus.put("res-1", 409);

        ExpirationJob.Result result = job().run(message -> { });

        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.alreadyHandled()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void oneFailureDoesNotStopTheRestAndIsReported() {
        overdueBody = "{\"reservationIds\":[\"res-1\",\"res-2\",\"res-3\"]}";
        expireStatus.put("res-2", 500);

        ExpirationJob.Result result = job().run(message -> { });

        assertThat(result.expired()).isEqualTo(2);
        assertThat(result.failed()).singleElement().asString().contains("res-2").contains("500");
        assertThat(requests).contains("POST /api/v1/reservations/res-3/expire");
    }

    @Test
    void signsAnAdminServiceTokenAndSendsOneCorrelationIdPerRun() {
        overdueBody = "{\"reservationIds\":[\"res-1\"]}";

        job().run(message -> { });

        assertThat(authorizations).hasSize(1);
        String token = authorizations.iterator().next().substring("Bearer ".length());
        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("svc-expiration");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(correlationIds).hasSize(1).allSatisfy(id -> assertThat(id).startsWith("lambda-expiration-"));
    }

    @Test
    void failsIfReservationsRejectsTheListing() {
        overdueStatus = 403;

        assertThatThrownBy(() -> job().run(message -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }

    @Test
    void refusesToStartWithoutConfiguration() {
        HttpClient http = HttpClient.newHttpClient();

        assertThatThrownBy(() -> new ExpirationJob(null, SECRET, http, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RESERVATIONS_URL");
        assertThatThrownBy(() -> new ExpirationJob("http://localhost", "corta", http, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("URBANMOVE_JWT_SECRET");
    }

    private ExpirationJob job() {
        return new ExpirationJob("http://127.0.0.1:" + reservations.getAddress().getPort(), SECRET,
                HttpClient.newHttpClient(), Clock.systemUTC());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        correlationIds.add(exchange.getRequestHeaders().getFirst(ExpirationJob.CORRELATION_HEADER));
        int status;
        byte[] body = new byte[0];
        if (path.endsWith("/overdue")) {
            status = overdueStatus;
            body = overdueBody.getBytes(StandardCharsets.UTF_8);
        } else {
            String reservationId = path.split("/")[4];
            status = expireStatus.getOrDefault(reservationId, 200);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
