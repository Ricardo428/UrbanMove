package es.ulpgc.urbanmove.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.common.security.Role;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class ProfilesApiTest extends AbstractIntegrationTest {

    private static final String ME = "/api/v1/profiles/me";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // --- crear y actualizar ---

    @Test
    void theFirstPutCreatesTheProfile() {
        String user = newUser();

        ResponseEntity<Map> response = put(user, validBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("userId", user).containsEntry("status", "ACTIVE");
        assertThat(response.getBody().get("updatedAt")).isNotNull();
        assertThat(get(user).getBody()).containsEntry("firstName", "Ana")
                .containsEntry("lastName", "García")
                .containsEntry("phone", "+34600123456")
                .containsEntry("status", "ACTIVE");
        assertThat((Map) get(user).getBody().get("preferences")).containsEntry("newsletter", true)
                .containsEntry("language", "es");
    }

    @Test
    void aSecondPutUpdatesTheProfile() {
        String user = newUser();
        put(user, validBody());
        Map<String, Object> changes = validBody();
        changes.put("firstName", "Ana María");
        changes.put("phone", "600123456");
        changes.put("preferences", Map.of("newsletter", false, "language", "en"));

        ResponseEntity<Map> response = put(user, changes);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("userId", user);
        ResponseEntity<Map> profile = get(user);
        assertThat(profile.getBody()).containsEntry("firstName", "Ana María").containsEntry("phone", "600123456");
        assertThat((Map) profile.getBody().get("preferences")).containsEntry("newsletter", false)
                .containsEntry("language", "en");
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    void phoneAndPreferencesAreOptional() {
        String user = newUser();
        Map<String, Object> minimal = new HashMap<>();
        minimal.put("firstName", "Luis");
        minimal.put("lastName", "Pérez");

        ResponseEntity<Map> response = put(user, minimal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> profile = get(user);
        assertThat(profile.getBody()).containsEntry("phone", null);
        assertThat((Map) profile.getBody().get("preferences")).containsEntry("newsletter", false)
                .containsEntry("language", null);
    }

    @Test
    void putRejectsInvalidBodies() {
        Map<String, Object> noFirstName = validBody();
        noFirstName.remove("firstName");
        Map<String, Object> noLastName = validBody();
        noLastName.remove("lastName");
        Map<String, Object> blankName = validBody();
        blankName.put("firstName", "   ");
        Map<String, Object> letters = validBody();
        letters.put("phone", "no-es-un-telefono");
        Map<String, Object> tooShort = validBody();
        tooShort.put("phone", "+123");

        for (Map<String, Object> invalid : List.of(noFirstName, noLastName, blankName, letters, tooShort)) {
            String user = newUser();

            ResponseEntity<Map> response = put(user, invalid);

            assertThat(response.getStatusCode()).as(invalid.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "BAD_REQUEST");
            assertThat(count(user)).isZero();
        }
    }

    @Test
    void putWithoutTokenIsUnauthorized() {
        ResponseEntity<Map> response = rest.exchange(ME, HttpMethod.PUT, new HttpEntity<>(validBody()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void simultaneousFirstPutsOfTheSameUserCreateOneProfile() throws Exception {
        String user = newUser();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return (HttpStatus) put(user, validBody()).getStatusCode();
            }));
        }
        start.countDown();
        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(HttpStatus.CREATED);
        assertThat(statuses).filteredOn(status -> status != HttpStatus.CREATED).containsOnly(HttpStatus.OK);
        assertThat(count(user)).isEqualTo(1);
    }

    // --- consultar ---

    @Test
    void aUserWithoutProfileGetsNotFound() {
        ResponseEntity<Map> response = get(newUser());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "NOT_FOUND")
                .containsEntry("message", "El usuario todavía no tiene perfil");
    }

    @Test
    void eachUserOnlySeesHisOwnProfile() {
        String ana = newUser();
        String luis = newUser();
        put(ana, validBody());

        assertThat(get(ana).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(luis).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anyRoleCanHaveAProfile() {
        String technician = newUser();

        ResponseEntity<Map> response = rest.exchange(ME, HttpMethod.PUT,
                as(technician, Role.MAINTENANCE, validBody()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void getWithoutTokenIsUnauthorized() {
        assertThat(rest.getForEntity(ME, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- baja lógica ---

    @Test
    void deleteDeactivatesTheProfileWithoutRemovingTheData() {
        String user = newUser();
        put(user, validBody());

        ResponseEntity<Map> response = delete(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) response.getBody().get("message")).startsWith("Perfil desactivado correctamente");
        assertThat(get(user).getBody()).containsEntry("status", "INACTIVE").containsEntry("firstName", "Ana");
        assertThat(count(user)).isEqualTo(1);
    }

    @Test
    void aDeactivatedProfileCannotBeChanged() {
        String user = newUser();
        put(user, validBody());
        delete(user);
        Map<String, Object> changes = validBody();
        changes.put("firstName", "Otro");

        ResponseEntity<Map> response = put(user, changes);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "FORBIDDEN");
        assertThat(get(user).getBody()).containsEntry("firstName", "Ana");
    }

    @Test
    void deactivatingTwiceIsAConflict() {
        String user = newUser();
        put(user, validBody());
        delete(user);

        ResponseEntity<Map> response = delete(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CONFLICT");
    }

    @Test
    void deactivatingWithoutProfileIsNotFound() {
        assertThat(delete(newUser()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteWithoutTokenIsUnauthorized() {
        ResponseEntity<Map> response = rest.exchange(ME, HttpMethod.DELETE, HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- ayudas ---

    private ResponseEntity<Map> put(String user, Map<String, Object> body) {
        return rest.exchange(ME, HttpMethod.PUT, as(user, Role.USER, body), Map.class);
    }

    private ResponseEntity<Map> get(String user) {
        return rest.exchange(ME, HttpMethod.GET, as(user, Role.USER, null), Map.class);
    }

    private ResponseEntity<Map> delete(String user) {
        return rest.exchange(ME, HttpMethod.DELETE, as(user, Role.USER, null), Map.class);
    }

    private int count(String user) {
        return jdbc.queryForObject("SELECT count(*) FROM profiles WHERE user_id = ?", Integer.class, user);
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Ana");
        body.put("lastName", "García");
        body.put("phone", "+34600123456");
        body.put("preferences", Map.of("newsletter", true, "language", "es"));
        return body;
    }

    private static String newUser() {
        return "u-" + UUID.randomUUID();
    }
}
