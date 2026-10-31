package es.ulpgc.urbanmove.availability;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.VehiclePurchased;
import es.ulpgc.urbanmove.messaging.EventHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class AvailabilityApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/availability";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EventHandler<VehiclePurchased> purchased;

    @Autowired
    private EventHandler<ReservationCancelled> cancelled;

    @Autowired
    private EventHandler<ReservationExpired> expired;

    @Autowired
    private EventHandler<RideFinished> finished;

    // --- consulta ---

    @Test
    void listsOnlyAvailableVehiclesFilteredByZoneAndType() {
        String zone = "zone-" + UUID.randomUUID();
        String scooter = newVehicle(VehicleType.SCOOTER, zone);
        newVehicle(VehicleType.BIKE, zone);
        String reserved = newVehicle(VehicleType.SCOOTER, zone);
        reserve(reserved, "res-" + UUID.randomUUID());

        ResponseEntity<List> response = rest.exchange(BASE + "?zoneId=" + zone + "&type=SCOOTER", HttpMethod.GET,
                as(Role.USER, null), List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> vehicle = (Map<String, Object>) response.getBody().get(0);
        assertThat(vehicle).containsEntry("vehicleId", scooter)
                .containsEntry("vehicleType", "SCOOTER")
                .containsEntry("zoneId", zone)
                .containsEntry("status", "AVAILABLE")
                .doesNotContainKey("reservationId");
    }

    @Test
    void listingWithAnUnknownTypeIsABadRequest() {
        ResponseEntity<Map> response = rest.exchange(BASE + "?type=COCHE", HttpMethod.GET, as(Role.USER, null),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void requestsWithoutTokenAreUnauthorized() {
        ResponseEntity<Map> response = rest.getForEntity(BASE, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getShowsTheReservationThatHoldsTheVehicle() {
        String vehicle = newVehicle(VehicleType.BIKE, "zone-centro");
        reserve(vehicle, "res-7731");

        Map body = get(vehicle).getBody();

        assertThat(body).containsEntry("status", "RESERVED").containsEntry("reservationId", "res-7731");
    }

    @Test
    void getAnUnknownVehicleIsNotFound() {
        assertThat(get("v-no-existe").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- reservar ---

    @Test
    void reserveMovesAnAvailableVehicleToReserved() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");

        ResponseEntity<Map> response = reserve(vehicle, "res-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "RESERVED")
                .containsEntry("reservationId", "res-1")
                .containsEntry("zoneId", "zone-centro");
    }

    @Test
    void reservingAgainWithTheSameReservationIsIdempotent() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        reserve(vehicle, "res-1");

        assertThat(reserve(vehicle, "res-1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reserve(vehicle, "res-2").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reserveErrorsFollowTheContract() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");

        assertThat(post(vehicle, "reserve", Role.ADMIN, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(vehicle, "reserve", Role.USER, Map.of("reservationId", "res-1")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reserve("v-no-existe", "res-1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ofManySimultaneousReservationsOnlyOneWins() throws Exception {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        int attempts = 10;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Future<HttpStatusCode>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String reservation = "res-concurrent-" + i;
                Callable<HttpStatusCode> call = () -> {
                    go.await();
                    return reserve(vehicle, reservation).getStatusCode();
                };
                results.add(pool.submit(call));
            }
            go.countDown();

            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).filteredOn(HttpStatus.OK::equals).hasSize(1);
            assertThat(statuses).filteredOn(HttpStatus.CONFLICT::equals).hasSize(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- iniciar viaje ---

    @Test
    void startWithItsReservationMovesTheVehicleToInUse() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        reserve(vehicle, "res-1");

        ResponseEntity<Map> response = start(vehicle, "ride-1", "res-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "IN_USE").containsEntry("zoneId", "zone-centro");
        assertThat(start(vehicle, "ride-1", "res-1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void startWithoutReservationNeedsAnAvailableVehicle() {
        String free = newVehicle(VehicleType.BIKE, "zone-centro");
        String reserved = newVehicle(VehicleType.BIKE, "zone-centro");
        reserve(reserved, "res-1");

        assertThat(start(free, "ride-1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(start(reserved, "ride-2", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(start(reserved, "ride-3", "res-otra").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(start(free, "ride-4", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void startWithoutRideIdIsABadRequest() {
        String vehicle = newVehicle(VehicleType.BIKE, "zone-centro");

        assertThat(post(vehicle, "start", Role.ADMIN, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- cambios de estado de mantenimiento y recolocación ---

    @Test
    void maintenanceFollowsTheStateMachineUntilTheVehicleIsBackInService() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-norte");

        assertThat(changeState(vehicle, Role.MAINTENANCE, "WITHDRAWN", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changeState(vehicle, Role.MAINTENANCE, "CHARGING", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changeState(vehicle, Role.MAINTENANCE, "READY_FOR_SERVICE", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> back = changeState(vehicle, Role.MAINTENANCE, "AVAILABLE", null);

        assertThat(back.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(back.getBody()).containsEntry("status", "AVAILABLE").containsEntry("zoneId", "zone-norte");
    }

    @Test
    void relocationChangesTheZone() {
        String vehicle = newVehicle(VehicleType.BIKE, "zone-norte");
        changeState(vehicle, Role.ADMIN, "WITHDRAWN", null);

        ResponseEntity<Map> response = changeState(vehicle, Role.ADMIN, "READY_FOR_SERVICE", "zone-sur");

        assertThat(response.getBody()).containsEntry("status", "READY_FOR_SERVICE").containsEntry("zoneId", "zone-sur");
    }

    @Test
    void undeclaredTransitionsAreConflicts() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        reserve(vehicle, "res-1");
        start(vehicle, "ride-1", "res-1");

        ResponseEntity<Map> response = changeState(vehicle, Role.MAINTENANCE, "RETIRED", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).isEqualTo("CONFLICT");
        assertThat(changeState(vehicle, Role.MAINTENANCE, "AVAILABLE", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void changeStateErrorsFollowTheContract() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");

        assertThat(changeState(vehicle, Role.MAINTENANCE, "VOLANDO", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(changeState(vehicle, Role.USER, "WITHDRAWN", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(changeState("v-no-existe", Role.MAINTENANCE, "WITHDRAWN", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- eventos consumidos ---

    @Test
    void aRepeatedVehiclePurchasedDoesNotResetTheVehicle() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        reserve(vehicle, "res-1");

        handle(purchased, new VehiclePurchased(vehicle, VehicleType.SCOOTER, "Xiaomi", LocalDate.now(), "zone-sur"));

        assertThat(get(vehicle).getBody()).containsEntry("status", "RESERVED").containsEntry("zoneId", "zone-centro");
    }

    @Test
    void cancellingOrExpiringOnlyReleasesTheReservationThatHoldsTheVehicle() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        reserve(vehicle, "res-1");

        handle(cancelled, new ReservationCancelled("res-otra", vehicle, "u-1", Instant.now()));
        assertThat(get(vehicle).getBody()).containsEntry("status", "RESERVED");

        handle(expired, new ReservationExpired("res-1", vehicle, "u-1", Instant.now()));
        assertThat(get(vehicle).getBody()).containsEntry("status", "AVAILABLE").doesNotContainKey("reservationId");
    }

    @Test
    void aLateCancellationDoesNotReleaseAVehicleInUse() {
        String vehicle = newVehicle(VehicleType.SCOOTER, "zone-centro");
        reserve(vehicle, "res-1");
        start(vehicle, "ride-1", "res-1");

        handle(cancelled, new ReservationCancelled("res-1", vehicle, "u-1", Instant.now()));

        assertThat(get(vehicle).getBody()).containsEntry("status", "IN_USE");
    }

    @Test
    void rideFinishedLeavesTheVehicleInTheDestinationZoneAndChecksTheBattery() {
        String charged = newVehicle(VehicleType.SCOOTER, "zone-centro");
        String flat = newVehicle(VehicleType.SCOOTER, "zone-centro");
        start(charged, "ride-1", null);
        start(flat, "ride-2", null);

        handle(finished, rideFinished("ride-1", charged, 80));
        handle(finished, rideFinished("ride-2", flat, 5));

        assertThat(get(charged).getBody()).containsEntry("status", "AVAILABLE").containsEntry("zoneId", "zone-sur");
        assertThat(get(flat).getBody()).containsEntry("status", "LOW_BATTERY").containsEntry("zoneId", "zone-sur");
    }

    // --- utilidades ---

    private String newVehicle(VehicleType type, String zone) {
        String vehicleId = "v-" + UUID.randomUUID();
        handle(purchased, new VehiclePurchased(vehicleId, type, "Modelo", LocalDate.now(), zone));
        return vehicleId;
    }

    private static RideFinished rideFinished(String rideId, String vehicleId, int battery) {
        return new RideFinished(rideId, vehicleId, "u-1", "zone-centro", "zone-sur", Instant.now(), 12,
                new BigDecimal("2.40"), battery);
    }

    private static <T extends EventPayload> void handle(EventHandler<T> handler, T payload) {
        handler.handle(payload, EventEnvelope.of("test", payload, null));
    }

    private ResponseEntity<Map> get(String vehicleId) {
        return rest.exchange(BASE + "/" + vehicleId, HttpMethod.GET, as(Role.USER, null), Map.class);
    }

    private ResponseEntity<Map> reserve(String vehicleId, String reservationId) {
        return post(vehicleId, "reserve", Role.ADMIN, Map.of("reservationId", reservationId));
    }

    private ResponseEntity<Map> start(String vehicleId, String rideId, String reservationId) {
        Map<String, Object> body = reservationId == null
                ? Map.of("rideId", rideId)
                : Map.of("rideId", rideId, "reservationId", reservationId);
        return post(vehicleId, "start", Role.ADMIN, body);
    }

    private ResponseEntity<Map> changeState(String vehicleId, Role role, String state, String zoneId) {
        Map<String, Object> body = zoneId == null
                ? Map.of("state", state, "reason", "test")
                : Map.of("state", state, "reason", "test", "zoneId", zoneId);
        return rest.exchange(BASE + "/" + vehicleId + "/state", HttpMethod.PUT, as(role, body), Map.class);
    }

    private ResponseEntity<Map> post(String vehicleId, String action, Role role, Object body) {
        HttpEntity<Object> request = as(role, body);
        return rest.exchange(BASE + "/" + vehicleId + "/" + action, HttpMethod.POST, request, Map.class);
    }
}
