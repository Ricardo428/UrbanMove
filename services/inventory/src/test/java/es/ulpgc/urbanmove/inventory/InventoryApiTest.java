package es.ulpgc.urbanmove.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.common.security.Role;
import java.time.LocalDate;
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
class InventoryApiTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/inventory/vehicles";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // --- alta ---

    @Test
    void registerCreatesTheVehicleAndPublishesVehiclePurchased() {
        String serial = uniqueSerial();

        ResponseEntity<Map> response = register(Role.MAINTENANCE, body(serial, "SCOOTER", "zone-centro"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String vehicleId = (String) response.getBody().get("vehicleId");
        assertThat(vehicleId).matches("v-\\d{4,}");
        assertThat(response.getBody()).containsEntry("vehicleType", "SCOOTER")
                .containsEntry("serialNumber", serial)
                .containsEntry("model", "Xiaomi Pro 4")
                .containsEntry("purchaseDate", LocalDate.now().toString())
                .containsEntry("administrativeStatus", "ACTIVE");
        assertThat(outboxEvents(vehicleId)).singleElement().satisfies(event -> assertThat(event)
                .containsEntry("event_type", "VehiclePurchased")
                .containsEntry("zone_id", "zone-centro")
                .containsEntry("vehicle_type", "SCOOTER")
                .containsEntry("model", "Xiaomi Pro 4")
                .containsEntry("purchase_date", LocalDate.now().toString()));
    }

    @Test
    void anAdminCanRegisterToo() {
        ResponseEntity<Map> response = register(Role.ADMIN, body(uniqueSerial(), "BIKE", "zone-norte"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("vehicleType", "BIKE");
    }

    @Test
    void aNormalUserCannotRegisterAndNothingIsSaved() {
        String serial = uniqueSerial();

        ResponseEntity<Map> response = register(Role.USER, body(serial, "BIKE", "zone-centro"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "FORBIDDEN");
        assertThat(countBySerial(serial)).isZero();
    }

    @Test
    void registerWithoutTokenIsUnauthorized() {
        ResponseEntity<Map> response = rest.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(body(uniqueSerial(), "BIKE", "zone-centro")), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDuplicatedSerialNumberIsAConflictAndPublishesNothingNew() {
        String serial = uniqueSerial();
        ResponseEntity<Map> first = register(Role.MAINTENANCE, body(serial, "SCOOTER", "zone-centro"));
        String vehicleId = (String) first.getBody().get("vehicleId");

        ResponseEntity<Map> second = register(Role.MAINTENANCE, body(serial, "BIKE", "zone-sur"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("error", "CONFLICT");
        assertThat((String) second.getBody().get("message")).contains(serial);
        assertThat(second.getBody().get("correlationId")).isNotNull();
        assertThat(countBySerial(serial)).isEqualTo(1);
        assertThat(outboxEvents(vehicleId)).hasSize(1);
    }

    @Test
    void simultaneousRegistrationsOfTheSameSerialNumberCreateOnlyOneVehicle() throws Exception {
        String serial = uniqueSerial();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return (HttpStatus) register(Role.MAINTENANCE, body(serial, "SCOOTER", "zone-centro"))
                        .getStatusCode();
            }));
        }
        start.countDown();
        List<HttpStatus> statuses = new ArrayList<>();
        for (Future<HttpStatus> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(HttpStatus.CREATED);
        assertThat(statuses).filteredOn(status -> status != HttpStatus.CREATED).hasSize(threads - 1)
                .containsOnly(HttpStatus.CONFLICT);
        assertThat(countBySerial(serial)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM outbox_events e JOIN vehicles v
                            ON e.body -> 'payload' ->> 'vehicleId' = 'v-' || lpad(v.id::text, 4, '0')
                         WHERE v.serial_number = ?
                        """, Integer.class, serial)).isEqualTo(1);
    }

    @Test
    void registerRejectsInvalidBodies() {
        Map<String, Object> noModel = body(uniqueSerial(), "SCOOTER", "zone-centro");
        noModel.remove("model");
        Map<String, Object> noZone = body(uniqueSerial(), "SCOOTER", "zone-centro");
        noZone.remove("zoneId");
        Map<String, Object> unknownType = body(uniqueSerial(), "CAR", "zone-centro");
        Map<String, Object> futureDate = body(uniqueSerial(), "SCOOTER", "zone-centro");
        futureDate.put("purchaseDate", LocalDate.now().plusDays(2).toString());
        Map<String, Object> blankSerial = body("  ", "SCOOTER", "zone-centro");

        for (Map<String, Object> invalid : List.of(noModel, noZone, unknownType, futureDate, blankSerial)) {
            ResponseEntity<Map> response = register(Role.MAINTENANCE, invalid);

            assertThat(response.getStatusCode()).as(invalid.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "BAD_REQUEST");
        }
    }

    // --- consulta ---

    @Test
    void anyUserCanGetAVehicle() {
        String serial = uniqueSerial();
        String vehicleId = (String) register(Role.MAINTENANCE, body(serial, "SCOOTER", "zone-centro"))
                .getBody().get("vehicleId");

        ResponseEntity<Map> response = rest.exchange(BASE + "/" + vehicleId, HttpMethod.GET,
                as("u-1001", Role.USER, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("vehicleId", vehicleId)
                .containsEntry("serialNumber", serial)
                .containsEntry("administrativeStatus", "ACTIVE");
    }

    @Test
    void anUnknownOrMalformedVehicleIdIsNotFound() {
        for (String id : List.of("v-99999999", "abc", "v-", "v-12x")) {
            ResponseEntity<Map> response = rest.exchange(BASE + "/" + id, HttpMethod.GET,
                    as("u-1001", Role.USER, null), Map.class);

            assertThat(response.getStatusCode()).as(id).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "NOT_FOUND");
        }
    }

    @Test
    void getWithoutTokenIsUnauthorized() {
        ResponseEntity<Map> response = rest.getForEntity(BASE + "/v-0001", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- listado ---

    @Test
    void listFiltersByTypeAndAdministrativeStatus() {
        String bikeSerial = uniqueSerial();
        String scooterSerial = uniqueSerial();
        register(Role.MAINTENANCE, body(bikeSerial, "BIKE", "zone-centro"));
        String retiredId = (String) register(Role.MAINTENANCE, body(scooterSerial, "SCOOTER", "zone-centro"))
                .getBody().get("vehicleId");
        jdbc.update("UPDATE vehicles SET administrative_status = 'RETIRED' WHERE serial_number = ?", scooterSerial);

        List<Map> bikes = list(Role.MAINTENANCE, "?vehicleType=BIKE");
        assertThat(bikes).extracting(vehicle -> vehicle.get("serialNumber")).contains(bikeSerial)
                .doesNotContain(scooterSerial);
        assertThat(bikes).extracting(vehicle -> vehicle.get("vehicleType")).containsOnly("BIKE");

        List<Map> retired = list(Role.ADMIN, "?administrativeStatus=RETIRED");
        assertThat(retired).extracting(vehicle -> vehicle.get("vehicleId")).contains(retiredId);
        assertThat(retired).extracting(vehicle -> vehicle.get("administrativeStatus")).containsOnly("RETIRED");

        List<Map> activeScooters = list(Role.MAINTENANCE, "?vehicleType=SCOOTER&administrativeStatus=ACTIVE");
        assertThat(activeScooters).extracting(vehicle -> vehicle.get("serialNumber")).doesNotContain(scooterSerial);

        assertThat(list(Role.MAINTENANCE, "")).extracting(vehicle -> vehicle.get("serialNumber"))
                .contains(bikeSerial, scooterSerial);
    }

    @Test
    void listRejectsAnInvalidFilter() {
        ResponseEntity<Map> response = rest.exchange(BASE + "?vehicleType=CAR", HttpMethod.GET,
                as("u-2001", Role.MAINTENANCE, null), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "BAD_REQUEST");
    }

    @Test
    void aNormalUserCannotList() {
        ResponseEntity<Map> response = rest.exchange(BASE, HttpMethod.GET, as("u-1001", Role.USER, null),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- ayudas ---

    private ResponseEntity<Map> register(Role role, Map<String, Object> body) {
        return rest.exchange(BASE, HttpMethod.POST, as("u-2001", role, body), Map.class);
    }

    private List<Map> list(Role role, String query) {
        ResponseEntity<List> response = rest.exchange(BASE + query, HttpMethod.GET, as("u-2001", role, null),
                List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map>) response.getBody();
    }

    private static Map<String, Object> body(String serial, String type, String zone) {
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleType", type);
        body.put("serialNumber", serial);
        body.put("model", "Xiaomi Pro 4");
        body.put("purchaseDate", LocalDate.now().toString());
        body.put("zoneId", zone);
        return body;
    }

    private int countBySerial(String serial) {
        return jdbc.queryForObject("SELECT count(*) FROM vehicles WHERE serial_number = ?", Integer.class,
                serial.trim());
    }

    private List<Map<String, Object>> outboxEvents(String vehicleId) {
        return jdbc.queryForList("""
                        SELECT event_type,
                               body -> 'payload' ->> 'zoneId' AS zone_id,
                               body -> 'payload' ->> 'vehicleType' AS vehicle_type,
                               body -> 'payload' ->> 'model' AS model,
                               body -> 'payload' ->> 'purchaseDate' AS purchase_date
                          FROM outbox_events WHERE body -> 'payload' ->> 'vehicleId' = ?
                        """,
                vehicleId);
    }

    private static String uniqueSerial() {
        return "SN-" + UUID.randomUUID();
    }
}
