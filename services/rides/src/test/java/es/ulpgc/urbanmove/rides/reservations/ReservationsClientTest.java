package es.ulpgc.urbanmove.rides.reservations;

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
 * Prueba el cliente contra un servidor HTTP real y pequeño que hace de reservations: cómo traduce cada respuesta
 * y que un reservations lento o caído acaba en Unreachable en vez de colgar la petición del usuario.
 */
class ReservationsClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final String RESERVATION_JSON = """
            {"reservationId":"res-7731","vehicleId":"v-0042","userId":"u-1001","status":"CONFIRMED",
             "createdAt":"2026-10-13T10:15:00Z","expiresAt":"2026-10-13T10:31:00Z"}
            """;

    private final JwtTokenService tokens = new JwtTokenService("clave-solo-para-tests-de-rides-0123456789", 3600);
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>();

    private HttpServer server;
    private ReservationsClient client;

    @BeforeEach
    void startFakeReservations() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/reservations/", this::respond);
        server.start();
        client = clientFor("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeReservations() {
        server.stop(0);
    }

    @Test
    void aFoundReservationKeepsUserVehicleAndStatusAndTheCallUsesAnAdminServiceToken() {
        answer.set(new Answer(200, RESERVATION_JSON, Duration.ZERO));

        ReservationLookup lookup = client.find("res-7731");

        assertThat(lookup).isEqualTo(new ReservationLookup.Found(
                new ReservationSnapshot("u-1001", "v-0042", "CONFIRMED")));
        assertThat(receivedPath.get()).isEqualTo("/api/v1/reservations/res-7731");
        AuthenticatedUser caller = tokens.parse(receivedAuthorization.get().substring("Bearer ".length()));
        assertThat(caller).isEqualTo(new AuthenticatedUser(RidesServiceAccount.ID, Role.ADMIN));
    }

    @Test
    void anUnknownReservationIsNotFound() {
        answer.set(new Answer(404, "{\"error\":\"NOT_FOUND\"}", Duration.ZERO));

        assertThat(client.find("res-1")).isEqualTo(new ReservationLookup.NotFound());
    }

    @Test
    void aServerErrorIsUnreachable() {
        answer.set(new Answer(500, "{\"error\":\"INTERNAL_SERVER_ERROR\"}", Duration.ZERO));

        assertThat(client.find("res-1")).isEqualTo(new ReservationLookup.Unreachable());
    }

    @Test
    void aSlowReservationsIsCutByTheTimeout() {
        answer.set(new Answer(200, RESERVATION_JSON, TIMEOUT.multipliedBy(4)));

        assertThat(client.find("res-1")).isEqualTo(new ReservationLookup.Unreachable());
    }

    @Test
    void aReservationsThatIsDownIsUnreachable() {
        server.stop(0);

        assertThat(client.find("res-1")).isEqualTo(new ReservationLookup.Unreachable());
    }

    @Test
    void theSnapshotAnswersTheQuestionsRidesNeeds() {
        ReservationSnapshot confirmed = new ReservationSnapshot("u-1001", "v-0042", "CONFIRMED");

        assertThat(confirmed.belongsTo("u-1001")).isTrue();
        assertThat(confirmed.belongsTo("u-2002")).isFalse();
        assertThat(confirmed.isFor("v-0042")).isTrue();
        assertThat(confirmed.isFor("v-0043")).isFalse();
        assertThat(confirmed.isActive()).isTrue();
        assertThat(new ReservationSnapshot("u-1001", "v-0042", "PENDING").isActive()).isTrue();
        assertThat(new ReservationSnapshot("u-1001", "v-0042", "CANCELLED").isActive()).isFalse();
        assertThat(new ReservationSnapshot("u-1001", "v-0042", "USED").isActive()).isFalse();
    }

    private ReservationsClient clientFor(String url) {
        RidesProperties.Downstream reservations = new RidesProperties.Downstream(url, TIMEOUT);
        return new ReservationsClient(RestClient.builder(), new RidesProperties(null, reservations, null, null), tokens);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedPath.set(exchange.getRequestURI().getPath());
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
