package es.ulpgc.urbanmove.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Lo que comparten todas las llamadas entre servicios, contra un servidor HTTP real y pequeño. */
class ServiceRestClientsTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final JwtTokenService tokens = new JwtTokenService("clave-solo-para-tests-de-common-0123456789", 3600);
    private final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> receivedCorrelationId = new AtomicReference<>();
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);

    private HttpServer server;
    private RestClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        client = ServiceRestClients.create(RestClient.builder(), "http://localhost:" + server.getAddress().getPort(),
                TIMEOUT, "svc-prueba", tokens);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void everyCallCarriesAnAdminTokenOfTheCallingService() {
        client.get().uri("/algo").retrieve().toBodilessEntity();

        AuthenticatedUser caller = tokens.parse(receivedAuthorization.get().substring("Bearer ".length()));
        assertThat(caller).isEqualTo(new AuthenticatedUser("svc-prueba", Role.ADMIN));
    }

    @Test
    void theCorrelationIdOfTheCurrentRequestIsPropagated() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "c-prueba01");

        client.get().uri("/algo").retrieve().toBodilessEntity();

        assertThat(receivedCorrelationId.get()).isEqualTo("c-prueba01");
    }

    @Test
    void withoutACurrentRequestNoCorrelationIdIsSent() {
        client.get().uri("/algo").retrieve().toBodilessEntity();

        assertThat(receivedCorrelationId.get()).isNull();
    }

    @Test
    void aSlowServiceIsCutByTheTimeout() {
        delay.set(TIMEOUT.multipliedBy(4));

        assertThatThrownBy(() -> client.get().uri("/algo").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        receivedCorrelationId.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.HEADER));
        try {
            Thread.sleep(delay.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
