package es.ulpgc.urbanmove.rides.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.rides.RidesProperties;
import es.ulpgc.urbanmove.rides.RidesServiceAccount;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Prueba el cliente contra un servidor HTTP real y pequeño que hace de availability: cómo traduce cada respuesta
 * y que un availability lento o caído acaba en Unreachable en vez de colgar la petición del usuario.
 */
class AvailabilityClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final JwtTokenService tokens = new JwtTokenService("clave-solo-para-tests-de-rides-0123456789", 3600);
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>();

    private HttpServer server;
    private AvailabilityClient client;

    @BeforeEach
    void startFakeAvailability() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/availability/", this::respond);
        server.start();
        client = clientFor("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeAvailability() {
        server.stop(0);
    }

    @Test
    void aStartedVehicleReturnsItsZoneAndTheCallUsesAnAdminServiceToken() {
        answer.set(new Answer(200, """
                {"vehicleId":"v-0042","status":"IN_USE","zoneId":"zone-centro"}
                """, Duration.ZERO));

        StartOutcome outcome = client.start("v-0042", "ride-5501", "res-7731");

        assertThat(outcome).isEqualTo(new StartOutcome.Started("zone-centro"));
        assertThat(receivedPath.get()).isEqualTo("/api/v1/availability/v-0042/start");
        assertThat(receivedBody.get()).contains("\"rideId\":\"ride-5501\"", "\"reservationId\":\"res-7731\"");
        AuthenticatedUser caller = tokens.parse(receivedAuthorization.get().substring("Bearer ".length()));
        assertThat(caller).isEqualTo(new AuthenticatedUser(RidesServiceAccount.ID, Role.ADMIN));
    }

    @Test
    void aRideWithoutReservationSendsNoReservationId() {
        answer.set(new Answer(200, """
                {"vehicleId":"v-0042","status":"IN_USE","zoneId":"zone-centro"}
                """, Duration.ZERO));

        assertThat(client.start("v-0042", "ride-5501", null)).isEqualTo(new StartOutcome.Started("zone-centro"));
        assertThat(receivedBody.get()).doesNotContain("res-");
    }

    @Test
    void notFoundAndConflictKeepTheirMeaning() {
        answer.set(new Answer(404, "{\"error\":\"NOT_FOUND\"}", Duration.ZERO));
        assertThat(client.start("v-1", "ride-1", null)).isEqualTo(new StartOutcome.VehicleNotFound());

        answer.set(new Answer(409, "{\"error\":\"CONFLICT\"}", Duration.ZERO));
        assertThat(client.start("v-1", "ride-1", null)).isEqualTo(new StartOutcome.NotAvailable());
    }

    @Test
    void aServerErrorIsUnreachable() {
        answer.set(new Answer(500, "{\"error\":\"INTERNAL_SERVER_ERROR\"}", Duration.ZERO));

        assertThat(client.start("v-1", "ride-1", null)).isEqualTo(new StartOutcome.Unreachable());
    }

    @Test
    void aSlowAvailabilityIsCutByTheTimeout() {
        answer.set(new Answer(200, "{}", TIMEOUT.multipliedBy(4)));

        assertThat(client.start("v-1", "ride-1", null)).isEqualTo(new StartOutcome.Unreachable());
    }

    @Test
    void anAvailabilityThatIsDownIsUnreachable() {
        server.stop(0);

        assertThat(client.start("v-1", "ride-1", null)).isEqualTo(new StartOutcome.Unreachable());
    }

    private AvailabilityClient clientFor(String url) {
        RidesProperties.Downstream availability = new RidesProperties.Downstream(url, TIMEOUT);
        RidesProperties properties = new RidesProperties(availability, null, null, null);
        return new AvailabilityClient(RestClient.builder(), properties, tokens);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedPath.set(exchange.getRequestURI().getPath());
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
