package es.ulpgc.urbanmove.rides.zones;

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
 * Prueba el cliente contra un servidor HTTP real y pequeño que hace de zones: cómo traduce cada respuesta
 * y que un zones lento o caído acaba en Unreachable en vez de colgar la petición del usuario.
 */
class ZonesClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final String ZONE_JSON = """
            {"zoneId":"zone-norte","name":"Norte","parkingAllowed":true,"active":true,
             "center":{"lat":28.1235,"lon":-15.4363},"radiusMeters":800}
            """;

    private final JwtTokenService tokens = new JwtTokenService("clave-solo-para-tests-de-rides-0123456789", 3600);
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<Answer> answer = new AtomicReference<>();

    private HttpServer server;
    private ZonesClient client;

    @BeforeEach
    void startFakeZones() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/zones/", this::respond);
        server.start();
        client = clientFor("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeZones() {
        server.stop(0);
    }

    @Test
    void aFoundZoneKeepsItsParkingRulesAndTheCallUsesTheServiceToken() {
        answer.set(new Answer(200, ZONE_JSON, Duration.ZERO));

        ZoneLookup lookup = client.find("zone-norte");

        assertThat(lookup).isEqualTo(new ZoneLookup.Found(new ZoneSnapshot("zone-norte", true, true)));
        assertThat(receivedPath.get()).isEqualTo("/api/v1/zones/zone-norte");
        AuthenticatedUser caller = tokens.parse(receivedAuthorization.get().substring("Bearer ".length()));
        assertThat(caller).isEqualTo(new AuthenticatedUser(RidesServiceAccount.ID, Role.ADMIN));
    }

    @Test
    void anUnknownZoneIsNotFound() {
        answer.set(new Answer(404, "{\"error\":\"NOT_FOUND\"}", Duration.ZERO));

        assertThat(client.find("zone-x")).isEqualTo(new ZoneLookup.NotFound());
    }

    @Test
    void aServerErrorIsUnreachable() {
        answer.set(new Answer(500, "{\"error\":\"INTERNAL_SERVER_ERROR\"}", Duration.ZERO));

        assertThat(client.find("zone-x")).isEqualTo(new ZoneLookup.Unreachable());
    }

    @Test
    void aSlowZonesIsCutByTheTimeout() {
        answer.set(new Answer(200, ZONE_JSON, TIMEOUT.multipliedBy(4)));

        assertThat(client.find("zone-norte")).isEqualTo(new ZoneLookup.Unreachable());
    }

    @Test
    void aZonesThatIsDownIsUnreachable() {
        server.stop(0);

        assertThat(client.find("zone-norte")).isEqualTo(new ZoneLookup.Unreachable());
    }

    @Test
    void onlyAnActiveZoneThatAllowsParkingAllowsParking() {
        assertThat(new ZoneSnapshot("z", true, true).allowsParking()).isTrue();
        assertThat(new ZoneSnapshot("z", false, true).allowsParking()).isFalse();
        assertThat(new ZoneSnapshot("z", true, false).allowsParking()).isFalse();
    }

    private ZonesClient clientFor(String url) {
        RidesProperties.Downstream zones = new RidesProperties.Downstream(url, TIMEOUT);
        return new ZonesClient(RestClient.builder(), new RidesProperties(null, null, zones, null), tokens);
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
