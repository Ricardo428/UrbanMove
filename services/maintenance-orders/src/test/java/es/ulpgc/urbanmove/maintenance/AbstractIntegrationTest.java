package es.ulpgc.urbanmove.maintenance;

import es.ulpgc.urbanmove.maintenance.availability.AvailabilityClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: un PostgreSQL real en Docker, compartido por todas las clases de test.
 * Availability se sustituye por un doble (su cliente HTTP tiene su propio test). La clave JWT es solo de test.
 * Sin cola configurada, el consumidor de eventos no arranca, y sin topic el relay
 * no publica: los handlers se prueban llamándolos directamente y los eventos se miran en outbox_events.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "urbanmove.security.jwt-secret=clave-solo-para-tests-de-maintenance-orders-0123456789")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @MockBean
    protected AvailabilityClient availability;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
