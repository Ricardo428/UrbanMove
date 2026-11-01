package es.ulpgc.urbanmove.zones;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.common.security.Role;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class ZonesApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/zones";

    @Autowired
    private TestRestTemplate rest;

    // --- crear ---

    @Test
    void anAdminCreatesAnActiveZone() {
        String zoneId = uniqueZoneId();

        ResponseEntity<Map> response = create(Role.ADMIN, body(zoneId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("zoneId", zoneId)
                .containsEntry("name", "Sur")
                .containsEntry("parkingAllowed", true)
                .containsEntry("active", true)
                .containsEntry("radiusMeters", 600);
        assertThat((Map) response.getBody().get("center")).containsEntry("lat", 28.0997)
                .containsEntry("lon", -15.4134);
    }

    @Test
    void parkingAllowedDefaultsToTrueWhenOmitted() {
        Map<String, Object> body = body(uniqueZoneId());
        body.remove("parkingAllowed");

        ResponseEntity<Map> response = create(Role.ADMIN, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("parkingAllowed", true);
    }

    @Test
    void onlyAnAdminCanCreateZones() {
        for (Role role : List.of(Role.USER, Role.MAINTENANCE)) {
            String zoneId = uniqueZoneId();

            ResponseEntity<Map> response = create(role, body(zoneId));

            assertThat(response.getStatusCode()).as(role.name()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).containsEntry("error", "FORBIDDEN");
            assertThat(get(Role.USER, zoneId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void creatingWithoutTokenIsUnauthorized() {
        ResponseEntity<Map> response = rest.exchange(BASE, HttpMethod.POST, new HttpEntity<>(body(uniqueZoneId())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDuplicatedZoneIdIsAConflict() {
        String zoneId = uniqueZoneId();
        create(Role.ADMIN, body(zoneId));

        ResponseEntity<Map> response = create(Role.ADMIN, body(zoneId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CONFLICT");
        assertThat((String) response.getBody().get("message")).contains(zoneId);
        assertThat(response.getBody().get("correlationId")).isNotNull();
    }

    @Test
    void createRejectsInvalidBodies() {
        Map<String, Object> noZoneId = body(uniqueZoneId());
        noZoneId.remove("zoneId");
        Map<String, Object> noName = body(uniqueZoneId());
        noName.remove("name");
        Map<String, Object> zeroRadius = body(uniqueZoneId());
        zeroRadius.put("radiusMeters", 0);
        Map<String, Object> negativeRadius = body(uniqueZoneId());
        negativeRadius.put("radiusMeters", -5);
        Map<String, Object> noCenter = body(uniqueZoneId());
        noCenter.remove("center");
        Map<String, Object> badLatitude = body(uniqueZoneId());
        badLatitude.put("center", Map.of("lat", 123.0, "lon", 0.0));
        Map<String, Object> badZoneId = body("zona con espacios");

        for (Map<String, Object> invalid : List.of(noZoneId, noName, zeroRadius, negativeRadius, noCenter,
                badLatitude, badZoneId)) {
            ResponseEntity<Map> response = create(Role.ADMIN, invalid);

            assertThat(response.getStatusCode()).as(invalid.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "BAD_REQUEST");
        }
    }

    // --- consultar ---

    @Test
    void anyUserCanGetAZoneWithItsMetadata() {
        String zoneId = uniqueZoneId();
        create(Role.ADMIN, body(zoneId));

        ResponseEntity<Map> response = get(Role.USER, zoneId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("zoneId", zoneId)
                .containsEntry("parkingAllowed", true)
                .containsEntry("active", true)
                .containsEntry("radiusMeters", 600)
                .containsKey("center");
    }

    @Test
    void anUnknownZoneIsNotFound() {
        ResponseEntity<Map> response = get(Role.USER, "zone-no-existe");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "NOT_FOUND")
                .containsEntry("message", "La zona zone-no-existe no existe");
    }

    @Test
    void gettingWithoutTokenIsUnauthorized() {
        assertThat(rest.getForEntity(BASE, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity(BASE + "/zone-centro", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- listar ---

    @Test
    void listReturnsSummariesAndFiltersByActive() {
        String activeId = uniqueZoneId();
        String inactiveId = uniqueZoneId();
        create(Role.ADMIN, body(activeId));
        create(Role.ADMIN, body(inactiveId));
        update(Role.ADMIN, inactiveId, updateBody(true, false));

        List<Map> all = list(Role.USER, "");
        assertThat(all).extracting(zone -> zone.get("zoneId")).contains(activeId, inactiveId);
        assertThat(all).allSatisfy(zone -> assertThat(zone).containsKeys("zoneId", "name", "parkingAllowed", "active")
                .doesNotContainKey("center"));

        List<Map> active = list(Role.USER, "?active=true");
        assertThat(active).extracting(zone -> zone.get("zoneId")).contains(activeId).doesNotContain(inactiveId);
        assertThat(active).extracting(zone -> zone.get("active")).containsOnly(true);

        List<Map> inactive = list(Role.USER, "?active=false");
        assertThat(inactive).extracting(zone -> zone.get("zoneId")).contains(inactiveId).doesNotContain(activeId);
    }

    @Test
    void listRejectsAnInvalidActiveFilter() {
        for (String value : List.of("maybe", "yes", "1", "TRUE")) {
            ResponseEntity<Map> response = rest.exchange(BASE + "?active=" + value, HttpMethod.GET,
                    as("u-1001", Role.USER, null), Map.class);

            assertThat(response.getStatusCode()).as(value).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // --- actualizar ---

    @Test
    void anAdminUpdatesAndDeactivatesAZone() {
        String zoneId = uniqueZoneId();
        create(Role.ADMIN, body(zoneId));

        ResponseEntity<Map> response = update(Role.ADMIN, zoneId, updateBody(false, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("zoneId", zoneId)
                .containsEntry("parkingAllowed", false)
                .containsEntry("active", false)
                .containsEntry("name", "Sur ampliada")
                .containsEntry("radiusMeters", 900);
        assertThat(get(Role.USER, zoneId).getBody()).containsEntry("active", false)
                .containsEntry("parkingAllowed", false);
    }

    @Test
    void onlyAnAdminCanUpdate() {
        String zoneId = uniqueZoneId();
        create(Role.ADMIN, body(zoneId));

        ResponseEntity<Map> response = update(Role.MAINTENANCE, zoneId, updateBody(false, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(Role.USER, zoneId).getBody()).containsEntry("parkingAllowed", true);
    }

    @Test
    void updatingAnUnknownZoneIsNotFound() {
        ResponseEntity<Map> response = update(Role.ADMIN, "zone-no-existe", updateBody(true, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateRejectsInvalidBodies() {
        String zoneId = uniqueZoneId();
        create(Role.ADMIN, body(zoneId));
        Map<String, Object> noActive = updateBody(true, true);
        noActive.remove("active");
        Map<String, Object> noName = updateBody(true, true);
        noName.remove("name");
        Map<String, Object> badRadius = updateBody(true, true);
        badRadius.put("radiusMeters", -1);

        for (Map<String, Object> invalid : List.of(noActive, noName, badRadius)) {
            ResponseEntity<Map> response = update(Role.ADMIN, zoneId, invalid);

            assertThat(response.getStatusCode()).as(invalid.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // --- ayudas ---

    private ResponseEntity<Map> create(Role role, Map<String, Object> body) {
        return rest.exchange(BASE, HttpMethod.POST, as("u-2001", role, body), Map.class);
    }

    private ResponseEntity<Map> get(Role role, String zoneId) {
        return rest.exchange(BASE + "/" + zoneId, HttpMethod.GET, as("u-2001", role, null), Map.class);
    }

    private ResponseEntity<Map> update(Role role, String zoneId, Map<String, Object> body) {
        return rest.exchange(BASE + "/" + zoneId, HttpMethod.PUT, as("u-2001", role, body), Map.class);
    }

    private List<Map> list(Role role, String query) {
        ResponseEntity<List> response = rest.exchange(BASE + query, HttpMethod.GET, as("u-2001", role, null),
                List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map>) response.getBody();
    }

    private static Map<String, Object> body(String zoneId) {
        Map<String, Object> body = new HashMap<>();
        body.put("zoneId", zoneId);
        body.put("name", "Sur");
        body.put("parkingAllowed", true);
        body.put("center", Map.of("lat", 28.0997, "lon", -15.4134));
        body.put("radiusMeters", 600);
        return body;
    }

    private static Map<String, Object> updateBody(boolean parkingAllowed, boolean active) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Sur ampliada");
        body.put("parkingAllowed", parkingAllowed);
        body.put("active", active);
        body.put("center", Map.of("lat", 28.1, "lon", -15.41));
        body.put("radiusMeters", 900);
        return body;
    }

    private static String uniqueZoneId() {
        return "zone-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
