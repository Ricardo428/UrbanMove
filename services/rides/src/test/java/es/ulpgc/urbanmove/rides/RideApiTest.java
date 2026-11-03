package es.ulpgc.urbanmove.rides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.rides.availability.StartOutcome;
import es.ulpgc.urbanmove.rides.reservations.ReservationLookup;
import es.ulpgc.urbanmove.rides.reservations.ReservationSnapshot;
import es.ulpgc.urbanmove.rides.zones.ZoneLookup;
import es.ulpgc.urbanmove.rides.zones.ZoneSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SuppressWarnings({"rawtypes", "unchecked"})
class RideApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/rides";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // --- iniciar sin reserva ---

    @Test
    void startingWithoutReservationPutsTheRideInProgressAndPublishesRideStarted() {
        String user = newUser();
        String vehicle = startableVehicle("zone-centro");

        ResponseEntity<Map> response = start(user, vehicle, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String rideId = (String) response.getBody().get("rideId");
        assertThat(rideId).startsWith("ride-");
        assertThat(response.getBody()).containsEntry("vehicleId", vehicle)
                .containsEntry("originZoneId", "zone-centro")
                .containsEntry("startTime", clock.instant().toString())
                .containsEntry("status", "IN_PROGRESS");
        verify(availability).start(eq(vehicle), eq(rideId), isNull());
        verify(reservations, never()).find(anyString());
        assertThat(outboxEvents(rideId)).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("event_type", "RideStarted")
                .containsEntry("user_id", user)
                .containsEntry("origin_zone_id", "zone-centro")
                .containsEntry("reservation_id", null));
    }

    // --- iniciar con reserva ---

    @Test
    void startingWithTheUsersActiveReservationPublishesRideStartedWithTheReservation() {
        String user = newUser();
        String vehicle = startableVehicle("zone-centro");
        String reservation = reservationOf(user, vehicle, "CONFIRMED");

        ResponseEntity<Map> response = start(user, vehicle, reservation);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String rideId = (String) response.getBody().get("rideId");
        verify(availability).start(vehicle, rideId, reservation);
        assertThat(outboxEvents(rideId)).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("reservation_id", reservation));
    }

    @Test
    void aReservationOfAnotherUserIsForbiddenAndNothingIsSaved() {
        String vehicle = startableVehicle("zone-centro");
        String reservation = reservationOf(newUser(), vehicle, "CONFIRMED");

        assertThat(start(newUser(), vehicle, reservation).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusesOfVehicle(vehicle)).isEmpty();
        verify(availability, never()).start(eq(vehicle), anyString(), any());
    }

    @Test
    void aReservationOfAnotherVehicleIsAConflictAndNothingIsSaved() {
        String user = newUser();
        String vehicle = startableVehicle("zone-centro");
        String reservation = reservationOf(user, "v-" + UUID.randomUUID(), "CONFIRMED");

        assertThat(start(user, vehicle, reservation).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusesOfVehicle(vehicle)).isEmpty();
        verify(availability, never()).start(eq(vehicle), anyString(), any());
    }

    @Test
    void aReservationThatIsNoLongerActiveIsAConflict() {
        String user = newUser();
        String vehicle = startableVehicle("zone-centro");
        String reservation = reservationOf(user, vehicle, "CANCELLED");

        assertThat(start(user, vehicle, reservation).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusesOfVehicle(vehicle)).isEmpty();
    }

    @Test
    void anUnknownReservationIsNotFound() {
        String vehicle = startableVehicle("zone-centro");
        when(reservations.find("res-404")).thenReturn(new ReservationLookup.NotFound());

        assertThat(start(newUser(), vehicle, "res-404").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusesOfVehicle(vehicle)).isEmpty();
    }

    @Test
    void ifReservationsDoesNotAnswerNothingIsSaved() {
        String vehicle = startableVehicle("zone-centro");
        when(reservations.find("res-503")).thenReturn(new ReservationLookup.Unreachable());

        ResponseEntity<Map> response = start(newUser(), vehicle, "res-503");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(statusesOfVehicle(vehicle)).isEmpty();
    }

    // --- respuestas de availability ---

    @Test
    void aVehicleThatIsNotAvailableLeavesTheRideRejectedAndPublishesNothing() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.NotAvailable());

        assertThat(start(newUser(), vehicle, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusesOfVehicle(vehicle)).containsExactly("REJECTED");
        assertThat(outboxEventsOfVehicle(vehicle)).isEmpty();
    }

    @Test
    void anUnknownVehicleIsNotFoundAndTheRideIsRejected() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.VehicleNotFound());

        assertThat(start(newUser(), vehicle, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusesOfVehicle(vehicle)).containsExactly("REJECTED");
    }

    @Test
    void ifAvailabilityDoesNotAnswerTheRideStaysStartingAndPublishesNothing() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.Unreachable());

        ResponseEntity<Map> response = start(newUser(), vehicle, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("error")).isEqualTo("SERVICE_UNAVAILABLE");
        // El cliente HTTP del test (Apache HttpClient 5) reintenta una vez ante un 503; el reintento reutiliza
        // el viaje STARTING, así que solo hay uno.
        assertThat(statusesOfVehicle(vehicle)).containsExactly("STARTING");
        assertThat(outboxEventsOfVehicle(vehicle)).isEmpty();
    }

    // --- reintento tras un 503 ---

    @Test
    void retryingAfterAvailabilityFailedReusesTheSameRideAndRideId() {
        String user = newUser();
        String vehicle = "v-" + UUID.randomUUID();
        AtomicBoolean availabilityUp = new AtomicBoolean(false);
        when(availability.start(eq(vehicle), anyString(), any())).thenAnswer(call -> availabilityUp.get()
                ? new StartOutcome.Started("zone-centro")
                : new StartOutcome.Unreachable());

        assertThat(start(user, vehicle, null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        availabilityUp.set(true);
        ResponseEntity<Map> retry = start(user, vehicle, null);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String rideId = (String) retry.getBody().get("rideId");
        assertThat(statusesOfVehicle(vehicle)).containsExactly("IN_PROGRESS");
        ArgumentCaptor<String> rideIds = ArgumentCaptor.forClass(String.class);
        verify(availability, atLeast(2)).start(eq(vehicle), rideIds.capture(), isNull());
        assertThat(rideIds.getAllValues()).containsOnly(rideId);
        assertThat(outboxEvents(rideId)).hasSize(1);
    }

    @Test
    void aStartingRideOfAnotherUserIsNotReused() {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.Unreachable());
        start(newUser(), vehicle, null);

        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.NotAvailable());
        assertThat(start(newUser(), vehicle, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(statusesOfVehicle(vehicle)).contains("STARTING", "REJECTED");
    }

    @Test
    void startErrorsFollowTheContract() {
        String vehicle = startableVehicle("zone-centro");

        assertThat(rest.postForEntity(BASE + "/start", Map.of("vehicleId", vehicle), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(BASE + "/start", newUser(), Role.USER, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(BASE + "/start", "u-mant", Role.MAINTENANCE, Map.of("vehicleId", vehicle)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- finalizar ---

    @Test
    void finishingCompletesTheRideWithCostAndBatteryAndPublishesRideFinished() {
        String user = newUser();
        String rideId = rideInProgressOf(user);
        parkingZone("zone-norte");
        clock.advance(Duration.ofMinutes(30));

        ResponseEntity<Map> response = finish(rideId, user, "zone-norte");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("rideId", rideId)
                .containsEntry("endTime", clock.instant().toString())
                .containsEntry("durationMinutes", 30)
                .containsEntry("originZoneId", "zone-centro")
                .containsEntry("destinationZoneId", "zone-norte")
                .containsEntry("zoneValidated", true)
                .containsEntry("preliminaryCost", 4.5)
                .containsEntry("status", "COMPLETED");
        Map<String, Object> event = finishedEvent(rideId);
        assertThat(event).containsEntry("destination_zone_id", "zone-norte")
                .containsEntry("battery", "70")
                .containsEntry("user_id", user);
        // En el JSON el número puede salir como 4.5 o 4.50: se compara el valor, no el texto.
        assertThat(new BigDecimal((String) event.get("cost"))).isEqualByComparingTo("4.50");
        assertThat(get(rideId, user, Role.USER).getBody()).containsEntry("status", "COMPLETED");
    }

    @Test
    void ifZonesDoesNotAnswerTheRideIsFinishedAnywayWithoutValidatingTheZone() {
        String user = newUser();
        String rideId = rideInProgressOf(user);
        when(zones.find("zone-norte")).thenReturn(new ZoneLookup.Unreachable());

        ResponseEntity<Map> response = finish(rideId, user, "zone-norte");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("zoneValidated", false)
                .containsEntry("status", "COMPLETED");
        assertThat(finishedEvent(rideId)).containsEntry("destination_zone_id", "zone-norte");
    }

    @Test
    void aZoneWhereParkingIsNotAllowedIsRejectedAndTheRideGoesOn() {
        String user = newUser();
        String rideId = rideInProgressOf(user);
        when(zones.find("zone-parque"))
                .thenReturn(new ZoneLookup.Found(new ZoneSnapshot("zone-parque", false, true)));

        ResponseEntity<Map> response = finish(rideId, user, "zone-parque");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(get(rideId, user, Role.USER).getBody()).containsEntry("status", "IN_PROGRESS");
        assertThat(eventTypes(rideId)).containsExactly("RideStarted");
    }

    @Test
    void anUnknownZoneIsNotFoundAndTheRideGoesOn() {
        String user = newUser();
        String rideId = rideInProgressOf(user);
        when(zones.find("zone-x")).thenReturn(new ZoneLookup.NotFound());

        assertThat(finish(rideId, user, "zone-x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(rideId, user, Role.USER).getBody()).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void onlyTheOwnerCanFinishTheRide() {
        String rideId = rideInProgressOf(newUser());
        parkingZone("zone-norte");

        assertThat(finish(rideId, newUser(), "zone-norte").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void finishingTwiceIsAConflictAndPublishesOnlyOneRideFinished() {
        String user = newUser();
        String rideId = rideInProgressOf(user);
        parkingZone("zone-norte");

        assertThat(finish(rideId, user, "zone-norte").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(finish(rideId, user, "zone-norte").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(eventTypes(rideId)).containsExactly("RideStarted", "RideFinished");
    }

    @Test
    void aRideThatNeverStartedCannotBeFinished() {
        String user = newUser();
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.Unreachable());
        start(user, vehicle, null);
        String rideId = jdbc.queryForObject("SELECT 'ride-' || id FROM rides WHERE vehicle_id = ?", String.class,
                vehicle);
        parkingZone("zone-norte");

        assertThat(finish(rideId, user, "zone-norte").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void finishErrorsFollowTheContract() {
        String user = newUser();
        String rideId = rideInProgressOf(user);

        assertThat(post(BASE + "/" + rideId + "/finish", user, Role.USER, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(finish("ride-999999", user, "zone-norte").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- consultar ---

    @Test
    void onlyTheOwnerOrAnAdminCanSeeARide() {
        String owner = newUser();
        String rideId = (String) start(owner, startableVehicle("zone-centro"), null).getBody().get("rideId");

        ResponseEntity<Map> mine = get(rideId, owner, Role.USER);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).containsEntry("rideId", rideId)
                .containsEntry("userId", owner)
                .containsEntry("originZoneId", "zone-centro")
                .containsEntry("status", "IN_PROGRESS");

        assertThat(get(rideId, "u-admin", Role.ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(rideId, newUser(), Role.USER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anUnknownRideIsNotFound() {
        assertThat(get("ride-999999", newUser(), Role.USER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("no-es-un-viaje", newUser(), Role.USER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- ayudas ---

    private static String newUser() {
        return "u-" + UUID.randomUUID();
    }

    private String startableVehicle(String zone) {
        String vehicle = "v-" + UUID.randomUUID();
        when(availability.start(eq(vehicle), anyString(), any())).thenReturn(new StartOutcome.Started(zone));
        return vehicle;
    }

    private String reservationOf(String user, String vehicle, String status) {
        String reservation = "res-" + UUID.randomUUID();
        when(reservations.find(reservation))
                .thenReturn(new ReservationLookup.Found(new ReservationSnapshot(user, vehicle, status)));
        return reservation;
    }

    private ResponseEntity<Map> start(String user, String vehicle, String reservation) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", vehicle);
        if (reservation != null) {
            body.put("reservationId", reservation);
        }
        return post(BASE + "/start", user, Role.USER, body);
    }

    private String rideInProgressOf(String user) {
        return (String) start(user, startableVehicle("zone-centro"), null).getBody().get("rideId");
    }

    private void parkingZone(String zoneId) {
        when(zones.find(zoneId)).thenReturn(new ZoneLookup.Found(new ZoneSnapshot(zoneId, true, true)));
    }

    private ResponseEntity<Map> finish(String rideId, String user, String destinationZoneId) {
        return post(BASE + "/" + rideId + "/finish", user, Role.USER, Map.of("destinationZoneId", destinationZoneId));
    }

    private Map<String, Object> finishedEvent(String rideId) {
        return jdbc.queryForMap("""
                        SELECT body -> 'payload' ->> 'userId' AS user_id,
                               body -> 'payload' ->> 'destinationZoneId' AS destination_zone_id,
                               body -> 'payload' ->> 'batteryLevelPercent' AS battery,
                               body -> 'payload' ->> 'preliminaryCost' AS cost
                          FROM outbox_events
                         WHERE event_type = 'RideFinished' AND body -> 'payload' ->> 'rideId' = ?
                        """,
                rideId);
    }

    private List<String> eventTypes(String rideId) {
        return jdbc.queryForList("""
                        SELECT event_type FROM outbox_events
                         WHERE body -> 'payload' ->> 'rideId' = ? ORDER BY created_at
                        """,
                String.class, rideId);
    }

    private ResponseEntity<Map> get(String rideId, String user, Role role) {
        return rest.exchange(BASE + "/" + rideId, HttpMethod.GET, as(user, role, null), Map.class);
    }

    private ResponseEntity<Map> post(String url, String user, Role role, Object body) {
        return rest.exchange(url, HttpMethod.POST, as(user, role, body), Map.class);
    }

    private List<Map<String, Object>> outboxEvents(String rideId) {
        return jdbc.queryForList("""
                        SELECT event_type,
                               body -> 'payload' ->> 'userId' AS user_id,
                               body -> 'payload' ->> 'originZoneId' AS origin_zone_id,
                               body -> 'payload' ->> 'reservationId' AS reservation_id
                          FROM outbox_events WHERE body -> 'payload' ->> 'rideId' = ?
                        """,
                rideId);
    }

    private List<Map<String, Object>> outboxEventsOfVehicle(String vehicleId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE body -> 'payload' ->> 'vehicleId' = ?",
                vehicleId);
    }

    private List<String> statusesOfVehicle(String vehicleId) {
        return jdbc.queryForList("SELECT status FROM rides WHERE vehicle_id = ?", String.class, vehicleId);
    }
}
