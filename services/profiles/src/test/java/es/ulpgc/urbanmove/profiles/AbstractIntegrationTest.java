package es.ulpgc.urbanmove.profiles;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: un PostgreSQL real en Docker, compartido por todas las clases de test.
 * La clave JWT es solo de test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "urbanmove.security.jwt-secret=clave-solo-para-tests-de-profiles-0123456789")
abstract class AbstractIntegrationTest {

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

    @Autowired
    private JwtTokenService tokens;

    /** Petición con el token de un usuario con ese rol. */
    protected HttpEntity<Object> as(String userId, Role role, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(userId, role));
        return new HttpEntity<>(body, headers);
    }
}
