package es.ulpgc.urbanmove.maintenance.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.maintenance.MaintenanceProperties;
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
 * Prueba el cliente contra un servidor HTTP real y pequeño que hace de availability: qué pide, cómo traduce cada
 * respuesta y que un availability lento o caído acaba en Unreachable en vez de colgar la petición del operario.
 */
class AvailabilityClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final JwtTokenService tokens =
            new JwtTokenService("clave-solo-para-tests-de-maintenance-orders-0123456789", 3600);
    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
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
    void aChangedStateReturnsTheZoneAndTheCallIsAPutWithAnAdminServiceToken() {
        answer.set(new Answer(200, """
                {"vehicleId":"v-0042","status":"WITHDRAWN","zoneId":"zone-norte"}
                """, Duration.ZERO));

        StateOutcome outcome = client.changeState("v-0042", "WITHDRAWN", "Orden de mantenimiento mo-310");

        assertThat(outcome).isEqualTo(new StateOutcome.Changed("zone-norte"));
        assertThat(receivedMethod.get()).isEqualTo("PUT");
        assertThat(receivedPath.get()).isEqualTo("/api/v1/availability/v-0042/state");
        assertThat(receivedBody.get()).contains("\"state\":\"WITHDRAWN\"")
                .contains("\"reason\":\"Orden de mantenimiento mo-310\"");
        AuthenticatedUser caller = tokens.parse(receivedAuthorization.get().substring("Bearer ".length()));
        assertThat(caller).isEqualTo(new AuthenticatedUser(AvailabilityClient.SERVICE_ID, Role.ADMIN));
    }

    @Test
    void notFoundIsKeptApart() {
        answer.set(new Answer(404, "{\"error\":\"NOT_FOUND\",\"message\":\"No existe\"}", Duration.ZERO));

        assertThat(client.changeState("v-1", "WITHDRAWN", "x")).isEqualTo(new StateOutcome.NotFound());
    }

    @Test
    void aConflictCarriesTheMessageOfAvailability() {
        answer.set(new Answer(409, """
                {"error":"CONFLICT","message":"Transición no permitida: IN_USE a WITHDRAWN","correlationId":"c-1"}
                """, Duration.ZERO));

        assertThat(client.changeState("v-1", "WITHDRAWN", "x"))
                .isEqualTo(new StateOutcome.Rejected("Transición no permitida: IN_USE a WITHDRAWN"));
    }

    @Test
    void aConflictWithoutBodyStillSaysSomething() {
        answer.set(new Answer(409, "", Duration.ZERO));

        assertThat(client.changeState("v-1", "WITHDRAWN", "x"))
                .isInstanceOfSatisfying(StateOutcome.Rejected.class,
                        rejected -> assertThat(rejected.message()).isNotBlank());
    }

    @Test
    void aServerErrorIsUnreachable() {
        answer.set(new Answer(500, "{\"error\":\"INTERNAL_SERVER_ERROR\"}", Duration.ZERO));

        assertThat(client.changeState("v-1", "WITHDRAWN", "x")).isEqualTo(new StateOutcome.Unreachable());
    }

    @Test
    void aSlowAvailabilityIsCutByTheTimeout() {
        answer.set(new Answer(200, "{}", TIMEOUT.multipliedBy(4)));

        assertThat(client.changeState("v-1", "WITHDRAWN", "x")).isEqualTo(new StateOutcome.Unreachable());
    }

    @Test
    void anAvailabilityThatIsDownIsUnreachable() {
        server.stop(0);

        assertThat(client.changeState("v-1", "WITHDRAWN", "x")).isEqualTo(new StateOutcome.Unreachable());
    }

    private AvailabilityClient clientFor(String url) {
        MaintenanceProperties properties =
                new MaintenanceProperties(new MaintenanceProperties.Availability(url, TIMEOUT));
        return new AvailabilityClient(RestClient.builder(), properties, tokens);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedMethod.set(exchange.getRequestMethod());
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
        // Con cuerpo vacío, -1 indica "sin contenido": una longitud 0 dejaría la conexión esperando el cuerpo.
        exchange.sendResponseHeaders(current.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private record Answer(int status, String body, Duration delay) {
    }
}
