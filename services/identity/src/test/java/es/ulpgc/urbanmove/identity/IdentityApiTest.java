package es.ulpgc.urbanmove.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class IdentityApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/identity";
    private static final String PASSWORD = "Contrasena123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // --- registro ---

    @Test
    void registerCreatesAUserWithRoleUser() {
        ResponseEntity<Map> response = register(uniqueEmail(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("role")).isEqualTo("USER");
        assertThat((String) response.getBody().get("id")).startsWith("u-");
    }

    @Test
    void registerLeavesUserRegisteredInTheOutboxWithTheRequestCorrelationId() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "c-registro1");
        Map<String, Object> body = Map.of("email", uniqueEmail(), "password", PASSWORD);

        ResponseEntity<Map> response = rest.exchange(BASE + "/register", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> event = jdbc.queryForMap("""
                        SELECT event_type, body ->> 'source' AS source, body ->> 'correlationId' AS correlation,
                               body -> 'payload' ->> 'role' AS role
                          FROM outbox_events WHERE body -> 'payload' ->> 'userId' = ?
                        """,
                response.getBody().get("id"));
        assertThat(event).containsEntry("event_type", "UserRegistered")
                .containsEntry("source", "identity-service")
                .containsEntry("correlation", "c-registro1")
                .containsEntry("role", "USER");
    }

    @Test
    void aRejectedRegistrationPublishesNothing() {
        String email = uniqueEmail();
        register(email, PASSWORD);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);

        assertThat(register(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(before);
    }

    @Test
    void registerIgnoresARoleSentByTheClient() {
        Map<String, Object> body = Map.of("email", uniqueEmail(), "password", PASSWORD, "role", "ADMIN");

        ResponseEntity<Map> response = rest.postForEntity(BASE + "/register", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("role")).isEqualTo("USER");
    }

    @Test
    void registerRejectsADuplicatedEmail() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        ResponseEntity<Map> response = register(email.toUpperCase(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).isEqualTo("CONFLICT");
        assertThat(response.getBody().get("correlationId")).isNotNull();
    }

    @Test
    void registerRejectsAnInvalidEmailOrAShortPassword() {
        assertThat(register("no-es-un-email", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(register(uniqueEmail(), "corta").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- login ---

    @Test
    void loginReturnsAValidToken() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        ResponseEntity<Map> response = login(email, PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("USER");
        assertThat(response.getBody().get("expiresIn")).isEqualTo(3600);
        assertThat((String) response.getBody().get("token")).isNotBlank();
    }

    @Test
    void loginRejectsWrongCredentials() {
        String email = uniqueEmail();
        register(email, PASSWORD);

        assertThat(login(email, "otra-contrasena").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(uniqueEmail(), PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginRejectsAMissingField() {
        ResponseEntity<Map> response = rest.postForEntity(BASE + "/login", Map.of("email", "a@b.es"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- validate ---

    @Test
    void validateAcceptsAGoodTokenAndRejectsTheRest() {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String token = tokenOf(login(email, PASSWORD));

        ResponseEntity<Map> good = get("/validate", token);
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(good.getBody().get("valid")).isEqualTo(true);
        assertThat(good.getBody().get("role")).isEqualTo("USER");

        ResponseEntity<Map> bad = get("/validate", token + "x");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(bad.getBody().get("valid")).isEqualTo(false);

        ResponseEntity<Map> missing = get("/validate", null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- alta de usuarios con rol ---

    @Test
    void creatingUsersWithoutTokenIsUnauthorized() {
        ResponseEntity<Map> response = createUser(null, uniqueEmail(), "MAINTENANCE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("error")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void creatingUsersAsANormalUserIsForbidden() {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String token = tokenOf(login(email, PASSWORD));

        ResponseEntity<Map> response = createUser(token, uniqueEmail(), "MAINTENANCE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo("FORBIDDEN");
    }

    @Test
    void anAdminCanCreateAMaintenanceUserWhoCanThenLogIn() {
        String adminToken = tokenOf(login("admin@test.local", "ClaveAdminTest1"));
        String email = uniqueEmail();

        ResponseEntity<Map> created = createUser(adminToken, email, "MAINTENANCE");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("role")).isEqualTo("MAINTENANCE");
        assertThat(login(email, "Contrasena123").getBody().get("role")).isEqualTo("MAINTENANCE");
    }

    @Test
    void creatingAUserWithAnUnknownRoleIsABadRequest() {
        String adminToken = tokenOf(login("admin@test.local", "ClaveAdminTest1"));

        ResponseEntity<Map> response = createUser(adminToken, uniqueEmail(), "SUPERUSUARIO");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void creatingADuplicatedUserIsAConflict() {
        String adminToken = tokenOf(login("admin@test.local", "ClaveAdminTest1"));
        String email = uniqueEmail();
        createUser(adminToken, email, "USER");

        assertThat(createUser(adminToken, email, "USER").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anUnknownRouteReturnsTheCommonErrorFormat() {
        String email = uniqueEmail();
        register(email, PASSWORD);
        String token = tokenOf(login(email, PASSWORD));

        ResponseEntity<Map> response = get("/no-existe", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("error")).isEqualTo("NOT_FOUND");
    }

    // --- ayudas ---

    private ResponseEntity<Map> register(String email, String password) {
        return rest.postForEntity(BASE + "/register", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return rest.postForEntity(BASE + "/login", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> createUser(String token, String email, String role) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        Map<String, Object> body = Map.of("email", email, "password", PASSWORD, "role", role);
        return rest.exchange(BASE + "/users", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(BASE + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static String tokenOf(ResponseEntity<Map> loginResponse) {
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) loginResponse.getBody().get("token");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }
}
