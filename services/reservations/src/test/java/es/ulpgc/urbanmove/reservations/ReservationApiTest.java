package es.ulpgc.urbanmove.reservations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.messaging.EventHandler;
import es.ulpgc.urbanmove.reservations.availability.ReserveOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class ReservationApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/reservations";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventHandler<RideStarted> rideStarted;

    // --- crear ---

    @Test
    void createReservesTheVehicleAndPublishesVehicleReserved() {
        String user = newUser();
        String vehicle = availableVehicle("zone-centro");

        ResponseEntity<Map> response = create(user, vehicle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String reservationId = (String) response.getBody().get("reservationId");
        assertThat(reservationId).startsWith("res-");
        assertThat(response.getBody()).containsEntry("vehicleId", vehicle)
                .containsEntry("status", "PENDING")
                .containsEntry("expiresAt", clock.instant().plus(Duration.ofMinutes(5)).toString());
        assertThat(outboxEvents(reservationId)).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("event_type", "VehicleReserved")
                .containsEntry("user_id", user)
                .containsEntry("zone_id", "zone-centro"));
    }

    @Test
    void aVehicleThatIsNotAvailableLeavesTheReservationRejectedAndPublishesNothing() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.reserve(eq(vehicle), anyString())).thenReturn(new ReserveOutcome.NotAvailable());

        ResponseEntity<Map> response = create(newUser(), vehicle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusesOfVehicle(vehicle)).containsExactly("REJECTED");
        assertThat(outboxEventsOfVehicle(vehicle)).isEmpty();
    }

    @Test
    void anUnknownVehicleIsNotFoundAndTheReservationIsRejected() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.reserve(eq(vehicle), anyString())).thenReturn(new ReserveOutcome.VehicleNotFound());

        assertThat(create(newUser(), vehicle).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusesOfVehicle(vehicle)).containsExactly("REJECTED");
    }

    @Test
    void ifAvailabilityDoesNotAnswerTheReservationStaysPendingSoItCanExpire() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.reserve(eq(vehicle), anyString())).thenReturn(new ReserveOutcome.Unreachable());

        ResponseEntity<Map> response = create(newUser(), vehicle);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("error")).isEqualTo("SERVICE_UNAVAILABLE");
        // El cliente HTTP del test (Apache HttpClient 5) reintenta una vez ante un 503: puede haber dos reservas.
        assertThat(statusesOfVehicle(vehicle)).isNotEmpty().allMatch("PENDING"::equals);
        assertThat(outboxEventsOfVehicle(vehicle)).isEmpty();
    }

    @Test
    void createErrorsFollowTheContract() {
        String vehicle = availableVehicle("zone-centro");

        assertThat(rest.postForEntity(BASE, Map.of("vehicleId", vehicle), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(BASE, newUser(), Role.USER, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(BASE, "u-mant", Role.MAINTENANCE, Map.of("vehicleId", vehicle)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- consultar ---

    @Test
    void onlyTheOwnerOrAnAdminCanSeeAReservation() {
        String owner = newUser();
        String reservationId = reservationOf(owner);

        ResponseEntity<Map> mine = get(reservationId, owner, Role.USER);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).containsEntry("userId", owner)
                .containsEntry("status", "PENDING")
                .containsKeys("createdAt", "expiresAt");

        assertThat(get(reservationId, "u-admin", Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(reservationId, newUser(), Role.USER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("res-999999", owner, Role.USER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("cualquier-cosa", owner, Role.USER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- confirmar ---

    @Test
    void confirmingGivesFifteenMinutesFromTheConfirmation() {
        String owner = newUser();
        String reservationId = reservationOf(owner);
        clock.advance(Duration.ofMinutes(2));

        ResponseEntity<Map> response = action(reservationId, "confirm", owner, Role.USER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "CONFIRMED")
                .containsEntry("expiresAt", clock.instant().plus(Duration.ofMinutes(15)).toString());
        assertThat(action(reservationId, "confirm", owner, Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aPendingReservationCannotBeConfirmedOnceItHasExpired() {
        String owner = newUser();
        String reservationId = reservationOf(owner);
        clock.advance(Duration.ofMinutes(6));

        assertThat(action(reservationId, "confirm", owner, Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void onlyTheOwnerCanConfirmOrCancel() {
        String reservationId = reservationOf(newUser());
        String stranger = newUser();

        assertThat(action(reservationId, "confirm", stranger, Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(action(reservationId, "cancel", stranger, Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- cancelar ---

    @Test
    void cancellingPublishesReservationCancelledOnce() {
        String owner = newUser();
        String reservationId = reservationOf(owner);
        action(reservationId, "confirm", owner, Role.USER);

        ResponseEntity<Map> response = action(reservationId, "cancel", owner, Role.USER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "CANCELLED").containsKey("message");
        assertThat(action(reservationId, "cancel", owner, Role.USER).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(eventTypes(reservationId)).containsExactly("VehicleReserved", "ReservationCancelled");
    }

    // --- expirar ---

    @Test
    void anOverdueReservationExpiresOnceEvenIfTheLambdaRepeatsTheCall() {
        String reservationId = reservationOf(newUser());
        clock.advance(Duration.ofMinutes(5));

        ResponseEntity<Map> first = action(reservationId, "expire", "svc-expiration", Role.ADMIN);
        ResponseEntity<Map> repeated = action(reservationId, "expire", "svc-expiration", Role.ADMIN);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("status", "EXPIRED")
                .containsEntry("expiredAt", clock.instant().toString());
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventTypes(reservationId)).containsExactly("VehicleReserved", "ReservationExpired");
    }

    @Test
    void overdueListsOnlyActiveReservationsPastTheirDeadline() {
        String owner = newUser();
        String overdue = reservationOf(owner);
        String cancelled = reservationOf(owner);
        action(cancelled, "cancel", owner, Role.USER);
        clock.advance(Duration.ofMinutes(5));
        String fresh = reservationOf(owner);

        ResponseEntity<Map> response = rest.exchange(BASE + "/overdue", HttpMethod.GET,
                as("svc-expiration", Role.ADMIN, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) response.getBody().get("reservationIds"))
                .contains(overdue)
                .doesNotContain(cancelled, fresh);
    }

    @Test
    void overdueOnceExpiredNoLongerAppearsInTheList() {
        String reservationId = reservationOf(newUser());
        clock.advance(Duration.ofMinutes(5));
        action(reservationId, "expire", "svc-expiration", Role.ADMIN);

        ResponseEntity<Map> response = rest.exchange(BASE + "/overdue", HttpMethod.GET,
                as("svc-expiration", Role.ADMIN, null), Map.class);

        assertThat((List<String>) response.getBody().get("reservationIds")).doesNotContain(reservationId);
    }

    @Test
    void overdueIsOnlyForAdmins() {
        ResponseEntity<Map> asUser = rest.exchange(BASE + "/overdue", HttpMethod.GET,
                as(newUser(), Role.USER, null), Map.class);
        ResponseEntity<Map> withoutToken = rest.getForEntity(BASE + "/overdue", Map.class);

        assertThat(asUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(withoutToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expireErrorsFollowTheContract() {
        String owner = newUser();
        String notYet = reservationOf(owner);
        String cancelled = reservationOf(owner);
        action(cancelled, "cancel", owner, Role.USER);

        assertThat(action(notYet, "expire", owner, Role.USER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(action(notYet, "expire", "svc", Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(action(cancelled, "expire", "svc", Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(action("res-999999", "expire", "svc", Role.ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- RideStarted ---

    @Test
    void rideStartedMarksTheReservationAsUsedSoItNoLongerExpires() {
        String owner = newUser();
        String reservationId = reservationOf(owner);
        RideStarted event = new RideStarted("ride-1", "v-1", owner, reservationId, "zone-centro", Instant.now());

        rideStarted.handle(event, EventEnvelope.of("rides-service", event, null));
        clock.advance(Duration.ofMinutes(30));

        assertThat(get(reservationId, owner, Role.USER).getBody()).containsEntry("status", "USED");
        assertThat(action(reservationId, "expire", "svc", Role.ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // --- utilidades ---

    private static String newUser() {
        return "u-" + UUID.randomUUID();
    }

    private String availableVehicle(String zone) {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.reserve(eq(vehicle), anyString())).thenReturn(new ReserveOutcome.Reserved(zone));
        return vehicle;
    }

    private String reservationOf(String user) {
        return (String) create(user, availableVehicle("zone-centro")).getBody().get("reservationId");
    }

    private ResponseEntity<Map> create(String user, String vehicle) {
        return post(BASE, user, Role.USER, Map.of("vehicleId", vehicle));
    }

    private ResponseEntity<Map> get(String reservationId, String user, Role role) {
        return rest.exchange(BASE + "/" + reservationId, HttpMethod.GET, as(user, role, null), Map.class);
    }

    private ResponseEntity<Map> action(String reservationId, String action, String user, Role role) {
        return post(BASE + "/" + reservationId + "/" + action, user, role, null);
    }

    private ResponseEntity<Map> post(String url, String user, Role role, Object body) {
        return rest.exchange(url, HttpMethod.POST, as(user, role, body), Map.class);
    }

    private List<Map<String, Object>> outboxEvents(String reservationId) {
        return jdbc.queryForList("""
                        SELECT event_type, body -> 'payload' ->> 'userId' AS user_id,
                               body -> 'payload' ->> 'zoneId' AS zone_id
                          FROM outbox_events WHERE body -> 'payload' ->> 'reservationId' = ?
                        """,
                reservationId);
    }

    private List<String> eventTypes(String reservationId) {
        return jdbc.queryForList("""
                        SELECT event_type FROM outbox_events
                         WHERE body -> 'payload' ->> 'reservationId' = ? ORDER BY created_at
                        """,
                String.class, reservationId);
    }

    private List<Map<String, Object>> outboxEventsOfVehicle(String vehicleId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE body -> 'payload' ->> 'vehicleId' = ?",
                vehicleId);
    }

    private List<String> statusesOfVehicle(String vehicleId) {
        return jdbc.queryForList("SELECT status FROM reservations WHERE vehicle_id = ?", String.class, vehicleId);
    }
}
