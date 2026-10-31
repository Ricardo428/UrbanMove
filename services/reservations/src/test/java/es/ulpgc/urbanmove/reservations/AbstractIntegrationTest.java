package es.ulpgc.urbanmove.reservations;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.reservations.availability.AvailabilityClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: un PostgreSQL real en Docker, compartido por todas las clases de test.
 * Availability se sustituye por un doble (su cliente HTTP tiene su propio test) y el reloj se puede adelantar
 * para probar los plazos de caducidad. La clave JWT es solo de test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "urbanmove.security.jwt-secret=clave-solo-para-tests-de-reservations-0123456789")
@Import(AbstractIntegrationTest.TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    protected AvailabilityClient availability;

    @Autowired
    protected TestClock clock;

    @Autowired
    private JwtTokenService tokens;

    protected HttpEntity<Object> as(String userId, Role role, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(userId, role));
        return new HttpEntity<>(body, headers);
    }

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock();
        }
    }

    /** Reloj que solo avanza cuando el test lo pide. */
    public static class TestClock extends Clock {

        private Instant now = Instant.parse("2026-10-13T10:15:00Z");

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
