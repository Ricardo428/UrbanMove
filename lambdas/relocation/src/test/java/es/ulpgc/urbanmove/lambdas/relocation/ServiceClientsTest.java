package es.ulpgc.urbanmove.lambdas.relocation;

import static es.ulpgc.urbanmove.events.VehicleType.BIKE;
import static es.ulpgc.urbanmove.events.VehicleType.SCOOTER;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Prueba los clientes de analytics y availability contra un servidor HTTP local que hace de los dos. */
class ServiceClientsTest {

    static final String SECRET = "clave-solo-para-tests-de-la-lambda-0123456789";

    private HttpServer server;
    private String baseUrl;
    /** Respuesta de cada ruta (con su query): código y cuerpo. */
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> correlationIds = Collections.synchronizedList(new ArrayList<>());

    private final ServiceToken tokens = new ServiceToken(SECRET, Clock.systemUTC());
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // --- analytics ---

    @Test
    void readsTheDemandOfTheLastHourWithItsWindow() {
        answers.put("/api/v1/analytics/demand?windowMinutes=60", new Answer(200, """
                {"windowStart":"2026-10-13T15:00:00Z","windowEnd":"2026-10-13T16:00:00Z",
                 "dataUpTo":"2026-10-13T15:58:40Z",
                 "zones":[{"zoneId":"zone-centro","vehicleType":"SCOOTER","reservations":11,
                           "ridesWithoutReservation":3,"requests":14}]}
                """));

        Demand demand = analytics().currentDemand("c-1");

        assertThat(demand).isEqualTo(new Demand(Instant.parse("2026-10-13T15:00:00Z"),
                Instant.parse("2026-10-13T16:00:00Z"), Instant.parse("2026-10-13T15:58:40Z"),
                List.of(new ZoneDemand("zone-centro", SCOOTER, 14))));
    }

    @Test
    void anEmptyDataMartHasNoDataUpToAndNoZones() {
        answers.put("/api/v1/analytics/demand?windowMinutes=60", new Answer(200, """
                {"windowStart":"2026-10-13T15:00:00Z","windowEnd":"2026-10-13T16:00:00Z",
                 "dataUpTo":null,"zones":[]}
                """));

        Demand demand = analytics().currentDemand("c-1");

        assertThat(demand.dataUpTo()).isNull();
        assertThat(demand.zones()).isEmpty();
    }

    @Test
    void callsCarryAFreshAdminServiceTokenAndTheCorrelationId() {
        answers.put("/api/v1/analytics/demand?windowMinutes=60", new Answer(200, "{\"zones\":[]}"));

        analytics().currentDemand("lambda-relocation-1");

        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(authorizations.get(0).substring("Bearer ".length())).getPayload();
        assertThat(claims.getSubject()).isEqualTo("svc-relocation");
        assertThat(claims.get("role")).isEqualTo("ADMIN");
        assertThat(correlationIds).containsExactly("lambda-relocation-1");
    }

    @Test
    void anAnalyticsErrorIsAnUpstreamException() {
        answers.put("/api/v1/analytics/demand?windowMinutes=60", new Answer(503, ""));

        assertThatThrownBy(() -> analytics().currentDemand("c-1"))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("analytics respondió HTTP 503");
    }

    @Test
    void anAnalyticsThatDoesNotAnswerIsAnUpstreamException() {
        server.stop(0);

        assertThatThrownBy(() -> analytics().currentDemand("c-1"))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("analytics no responde");
    }

    // --- availability ---

    @Test
    void countsAvailableVehiclesByTypeAndZone() {
        answers.put("/api/v1/availability?type=BIKE", new Answer(200, """
                [{"vehicleId":"v-1","vehicleType":"BIKE","zoneId":"zone-centro","status":"AVAILABLE"},
                 {"vehicleId":"v-2","vehicleType":"BIKE","zoneId":"zone-centro","status":"AVAILABLE"},
                 {"vehicleId":"v-3","vehicleType":"BIKE","zoneId":"zone-norte","status":"AVAILABLE"}]
                """));
        answers.put("/api/v1/availability?type=SCOOTER", new Answer(200, """
                [{"vehicleId":"v-4","vehicleType":"SCOOTER","zoneId":"zone-norte","status":"AVAILABLE"}]
                """));

        AvailableCounts counts = availability().count("c-1");

        assertThat(counts.in("zone-centro", BIKE)).isEqualTo(2);
        assertThat(counts.in("zone-norte", BIKE)).isEqualTo(1);
        assertThat(counts.in("zone-norte", SCOOTER)).isEqualTo(1);
        assertThat(counts.in("zone-centro", SCOOTER)).isZero();
        assertThat(requests).containsExactly(
                "/api/v1/availability?type=BIKE", "/api/v1/availability?type=SCOOTER");
    }

    @Test
    void ifOneTypeFailsThereIsNoCountAtAll() {
        answers.put("/api/v1/availability?type=BIKE", new Answer(200, "[]"));
        answers.put("/api/v1/availability?type=SCOOTER", new Answer(500, ""));

        assertThatThrownBy(() -> availability().count("c-1"))
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("availability respondió HTTP 500");
    }

    // --- configuración ---

    @Test
    void aMissingOrShortSecretStopsTheLambda() {
        assertThatThrownBy(() -> new ServiceToken(null, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ServiceToken("corta", Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMissingUrlStopsTheLambda() {
        assertThatThrownBy(() -> new ServiceHttp("analytics", " ", http, tokens))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("analytics");
    }

    // --- utilidades ---

    private AnalyticsDemandSource analytics() {
        return new AnalyticsDemandSource(new ServiceHttp("analytics", baseUrl, http, tokens));
    }

    private AvailabilityCounter availability() {
        return new AvailabilityCounter(new ServiceHttp("availability", baseUrl, http, tokens));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        requests.add(path);
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        correlationIds.add(exchange.getRequestHeaders().getFirst(ServiceHttp.CORRELATION_HEADER));
        Answer answer = answers.getOrDefault(path, new Answer(404, ""));
        byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private record Answer(int status, String body) {
    }
}
