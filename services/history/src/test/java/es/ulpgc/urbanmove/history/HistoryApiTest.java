package es.ulpgc.urbanmove.history;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.EventType;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.messaging.EventHandler;
import java.time.Instant;
import java.util.List;
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

@SuppressWarnings({"rawtypes", "unchecked"})
class HistoryApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/history";
    private static final Instant T0 = Instant.parse("2026-10-13T10:15:00Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtTokenService tokens;

    @Autowired
    private EventHandler<VehicleReserved> vehicleReserved;

    @Autowired
    private EventHandler<ReservationCancelled> reservationCancelled;

    @Test
    void meReturnsOnlyTheEntriesOfTheUserInTheToken() {
        String alice = newId("u-a");
        String bob = newId("u-b");
        reserved(alice, newId("v"), T0);
        reserved(bob, newId("v"), T0);

        List<Map> entries = entries(get(BASE + "/me", alice, Role.USER));

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("type", "VehicleReserved").doesNotContainKey("userId");
    }

    @Test
    void entriesAreOrderedByWhenTheyHappenedNotByArrival() {
        String user = newId("u");
        String vehicle = newId("v");
        // La cancelación llega primero, aunque ocurrió dos minutos después de la reserva.
        cancelled(user, vehicle, T0.plusSeconds(120));
        reserved(user, vehicle, T0);

        List<Map> entries = entries(get(BASE + "/me", user, Role.USER));

        assertThat(entries).extracting(entry -> entry.get("type"))
                .containsExactly("ReservationCancelled", "VehicleReserved");
    }

    @Test
    void limitCutsTheMostRecentEntries() {
        String user = newId("u");
        String vehicle = newId("v");
        reserved(user, vehicle, T0);
        cancelled(user, vehicle, T0.plusSeconds(60));

        List<Map> entries = entries(get(BASE + "/me?limit=1", user, Role.USER));

        assertThat(entries).extracting(entry -> entry.get("type")).containsExactly("ReservationCancelled");
    }

    @Test
    void limitOutOfRangeIsABadRequest() {
        assertThat(get(BASE + "/me?limit=0", newId("u"), Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(BASE + "/me?limit=201", newId("u"), Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withoutTokenIsUnauthorized() {
        assertThat(rest.getForEntity(BASE + "/me", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void maintenanceSeesEverythingAboutAVehicleWithTheUser() {
        String user = newId("u");
        String vehicle = newId("v");
        reserved(user, vehicle, T0);

        ResponseEntity<Map> response = get(BASE + "/vehicles/" + vehicle, newId("m"), Role.MAINTENANCE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entries(response)).singleElement().satisfies(entry ->
                assertThat(entry).containsEntry("userId", user).containsEntry("vehicleId", vehicle));
    }

    @Test
    void aUserCannotReadTheHistoryOfAVehicle() {
        assertThat(get(BASE + "/vehicles/v-0042", newId("u"), Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnknownVehicleReturnsAnEmptyListNotNotFound() {
        ResponseEntity<Map> response = get(BASE + "/vehicles/" + newId("v"), newId("a"), Role.ADMIN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entries(response)).isEmpty();
    }

    private void reserved(String user, String vehicle, Instant when) {
        VehicleReserved payload = new VehicleReserved(newId("res"), vehicle, VehicleType.BIKE, user, "zone-centro", when);
        vehicleReserved.handle(payload, envelope(payload, when));
    }

    private void cancelled(String user, String vehicle, Instant when) {
        ReservationCancelled payload = new ReservationCancelled(newId("res"), vehicle, user, when);
        reservationCancelled.handle(payload, envelope(payload, when));
    }

    private ResponseEntity<Map> get(String url, String userId, Role role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokens.generate(userId, role));
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static List<Map> entries(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map>) response.getBody().get("entries");
    }

    private static EventEnvelope envelope(EventPayload payload, Instant timestamp) {
        EventType type = EventType.of(payload.getClass());
        return new EventEnvelope(UUID.randomUUID(), timestamp, "test-service", type.wireName(), type.version(),
                "c-test", EventJson.mapper().valueToTree(payload));
    }

    /** Ids distintos en cada test: la base se comparte entre tests y no se vacía. */
    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
