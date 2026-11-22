package es.ulpgc.urbanmove.relocation.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.relocation.RelocationProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Prueba el cliente contra un servidor HTTP real y pequeño que hace de availability: qué pide, cómo cuenta por
 * zona y que un availability lento o caído acaba en Unreachable en vez de dejar colgado al consumidor.
 */
class AvailabilityClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final JwtTokenService tokens =
            new JwtTokenService("clave-solo-para-tests-de-relocation-orders-0123456789", 3600);
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>();

    private HttpServer server;
    private AvailabilityClient client;

    @BeforeEach
    void startFakeAvailability() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/availability", this::respond);
        server.start();
        client = clientFor("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeAvailability() {
        server.stop(0);
    }

    @Test
    void countsTheAvailableVehiclesOfEachZoneWithAGetByTypeAndAnAdminServiceToken() {
        answer.set(new Answer(200, """
                [
                  {"vehicleId":"v-1","vehicleType":"SCOOTER","zoneId":"zone-norte","status":"AVAILABLE"},
                  {"vehicleId":"v-2","vehicleType":"SCOOTER","zoneId":"zone-norte","status":"AVAILABLE"},
                  {"vehicleId":"v-3","vehicleType":"SCOOTER","zoneId":"zone-sur","status":"AVAILABLE"}
                ]
                """, Duration.ZERO));

        CountOutcome outcome = client.countAvailableByZone(VehicleType.SCOOTER);

        assertThat(outcome).isEqualTo(new CountOutcome.Counted(Map.of("zone-norte", 2, "zone-sur", 1)));
        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedPath.get()).isEqualTo("/api/v1/availability");
        assertThat(receivedQuery.get()).isEqualTo("type=SCOOTER");
        AuthenticatedUser caller = tokens.parse(receivedAuthorization.get().substring("Bearer ".length()));
        assertThat(caller).isEqualTo(new AuthenticatedUser(AvailabilityClient.SERVICE_ID, Role.ADMIN));
    }

    @Test
    void aZoneWithoutVehiclesCountsAsZero() {
        answer.set(new Answer(200, "[]", Duration.ZERO));

        CountOutcome outcome = client.countAvailableByZone(VehicleType.BIKE);

        assertThat(outcome).isInstanceOfSatisfying(CountOutcome.Counted.class,
                counted -> assertThat(counted.in("zone-centro")).isZero());
    }

    @Test
    void aServerErrorIsUnreachable() {
        answer.set(new Answer(500, "{\"error\":\"INTERNAL_SERVER_ERROR\"}", Duration.ZERO));

        assertThat(client.countAvailableByZone(VehicleType.BIKE)).isEqualTo(new CountOutcome.Unreachable());
    }

    @Test
    void aRejectedServiceTokenIsUnreachable() {
        answer.set(new Answer(401, "{\"error\":\"UNAUTHORIZED\"}", Duration.ZERO));

        assertThat(client.countAvailableByZone(VehicleType.BIKE)).isEqualTo(new CountOutcome.Unreachable());
    }

    @Test
    void aSlowAvailabilityIsCutByTheTimeout() {
        answer.set(new Answer(200, "[]", TIMEOUT.multipliedBy(4)));

        assertThat(client.countAvailableByZone(VehicleType.BIKE)).isEqualTo(new CountOutcome.Unreachable());
    }

    @Test
    void anAvailabilityThatIsDownIsUnreachable() {
        server.stop(0);

        assertThat(client.countAvailableByZone(VehicleType.BIKE)).isEqualTo(new CountOutcome.Unreachable());
    }

    @Test
    void getsTheStateTypeAndZoneOfAVehicle() {
        answer.set(new Answer(200, """
                {"vehicleId":"v-0051","vehicleType":"SCOOTER","zoneId":"zone-norte","status":"AVAILABLE"}
                """, Duration.ZERO));

        VehicleOutcome outcome = client.get("v-0051");

        assertThat(outcome).isEqualTo(new VehicleOutcome.Found("v-0051", "SCOOTER", "zone-norte", "AVAILABLE"));
        assertThat(receivedMethod.get()).isEqualTo("GET");
        assertThat(receivedPath.get()).isEqualTo("/api/v1/availability/v-0051");
    }

    @Test
    void anUnknownVehicleIsNotFound() {
        answer.set(new Answer(404, "{\"error\":\"NOT_FOUND\"}", Duration.ZERO));

        assertThat(client.get("v-9999")).isEqualTo(new VehicleOutcome.NotFound());
    }

    @Test
    void aGetWithoutAnswerIsUnreachable() {
        answer.set(new Answer(200, "{}", TIMEOUT.multipliedBy(4)));

        assertThat(client.get("v-0051")).isEqualTo(new VehicleOutcome.Unreachable());
    }

    @Test
    void changesTheStateWithAPutThatCarriesStateReasonAndZone() {
        answer.set(new Answer(200, """
                {"vehicleId":"v-0051","status":"READY_FOR_SERVICE","zoneId":"zone-centro"}
                """, Duration.ZERO));

        StateOutcome outcome = client.changeState("v-0051", "READY_FOR_SERVICE", "zone-centro", "Orden ro-210");

        assertThat(outcome).isEqualTo(new StateOutcome.Changed("zone-centro"));
        assertThat(receivedMethod.get()).isEqualTo("PUT");
        assertThat(receivedPath.get()).isEqualTo("/api/v1/availability/v-0051/state");
        assertThat(receivedBody.get())
                .contains("\"state\":\"READY_FOR_SERVICE\"")
                .contains("\"zoneId\":\"zone-centro\"")
                .contains("\"reason\":\"Orden ro-210\"");
    }

    @Test
    void aForbiddenTransitionIsRejectedWithTheMessageOfAvailability() {
        answer.set(new Answer(409, """
                {"error":"CONFLICT","message":"No se puede pasar de RESERVED a WITHDRAWN","correlationId":"c-1"}
                """, Duration.ZERO));

        assertThat(client.changeState("v-0053", "WITHDRAWN", null, "Orden ro-210"))
                .isEqualTo(new StateOutcome.Rejected("No se puede pasar de RESERVED a WITHDRAWN"));
    }

    @Test
    void aPutToAnUnknownVehicleIsNotFound() {
        answer.set(new Answer(404, "{\"error\":\"NOT_FOUND\"}", Duration.ZERO));

        assertThat(client.changeState("v-9999", "WITHDRAWN", null, "Orden ro-210"))
                .isEqualTo(new StateOutcome.NotFound());
    }

    @Test
    void aPutWithoutAnswerIsUnreachableBecauseNobodyKnowsIfItWasApplied() {
        answer.set(new Answer(200, "{}", TIMEOUT.multipliedBy(4)));

        assertThat(client.changeState("v-0051", "WITHDRAWN", null, "Orden ro-210"))
                .isEqualTo(new StateOutcome.Unreachable());
    }

    private AvailabilityClient clientFor(String url) {
        RelocationProperties properties =
                new RelocationProperties(6, 2, new RelocationProperties.Availability(url, TIMEOUT));
        return new AvailabilityClient(RestClient.builder(), properties, tokens);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedMethod.set(exchange.getRequestMethod());
        receivedPath.set(exchange.getRequestURI().getPath());
        receivedQuery.set(exchange.getRequestURI().getQuery());
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Answer current = answer.get();
        try {
            Thread.sleep(current.delay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(current.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private record Answer(int status, String body, Duration delay) {
    }
}
