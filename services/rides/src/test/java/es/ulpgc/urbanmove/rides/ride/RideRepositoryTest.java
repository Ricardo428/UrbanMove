package es.ulpgc.urbanmove.rides.ride;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.rides.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Las transiciones de estado del viaje contra un PostgreSQL real: cada una solo vale desde su estado de partida. */
class RideRepositoryTest extends AbstractIntegrationTest {

    private static final Instant START = Instant.parse("2026-10-13T10:30:00Z");
    private static final Instant END = Instant.parse("2026-10-13T11:00:00Z");

    @Autowired
    private RideRepository rides;

    @Test
    void aNewRideIsSavedAsStartingWithoutOriginZone() {
        long id = rides.insertStarting("v-0042", "u-1001", "res-7731", START);

        Ride ride = rides.find(id).orElseThrow();
        assertThat(ride.status()).isEqualTo(RideStatus.STARTING);
        assertThat(ride.reservationId()).isEqualTo("res-7731");
        assertThat(ride.startTime()).isEqualTo(START);
        assertThat(ride.originZoneId()).isNull();
        assertThat(ride.endTime()).isNull();
    }

    @Test
    void aRideWithoutReservationKeepsTheReservationEmpty() {
        long id = rides.insertStarting("v-0042", "u-1001", null, START);

        assertThat(rides.find(id).orElseThrow().reservationId()).isNull();
    }

    @Test
    void markInProgressStoresTheOriginZoneOnlyOnce() {
        long id = rides.insertStarting("v-0042", "u-1001", null, START);

        assertThat(rides.markInProgress(id, "zone-centro")).isTrue();
        assertThat(rides.markInProgress(id, "zone-norte")).isFalse();

        Ride ride = rides.find(id).orElseThrow();
        assertThat(ride.status()).isEqualTo(RideStatus.IN_PROGRESS);
        assertThat(ride.originZoneId()).isEqualTo("zone-centro");
    }

    @Test
    void onlyAStartingRideCanBeRejected() {
        long rejected = rides.insertStarting("v-0042", "u-1001", null, START);
        long started = rides.insertStarting("v-0043", "u-1001", null, START);
        rides.markInProgress(started, "zone-centro");

        assertThat(rides.reject(rejected)).isTrue();
        assertThat(rides.reject(started)).isFalse();
        assertThat(rides.find(rejected).orElseThrow().status()).isEqualTo(RideStatus.REJECTED);
        assertThat(rides.find(started).orElseThrow().status()).isEqualTo(RideStatus.IN_PROGRESS);
    }

    @Test
    void completeStoresTheEndOfTheRideAndCannotBeRepeated() {
        long id = rides.insertStarting("v-0042", "u-1001", null, START);
        rides.markInProgress(id, "zone-centro");

        assertThat(rides.complete(id, "zone-norte", END, 30, true, new BigDecimal("4.50"), 85)).isTrue();
        assertThat(rides.complete(id, "zone-sur", END, 31, true, new BigDecimal("4.65"), 84)).isFalse();

        Ride ride = rides.find(id).orElseThrow();
        assertThat(ride.status()).isEqualTo(RideStatus.COMPLETED);
        assertThat(ride.destinationZoneId()).isEqualTo("zone-norte");
        assertThat(ride.endTime()).isEqualTo(END);
        assertThat(ride.durationMinutes()).isEqualTo(30);
        assertThat(ride.zoneValidated()).isTrue();
        assertThat(ride.preliminaryCost()).isEqualByComparingTo("4.50");
        assertThat(ride.batteryLevelPercent()).isEqualTo(85);
    }

    @Test
    void aRideThatNeverStartedCannotBeCompleted() {
        long id = rides.insertStarting("v-0042", "u-1001", null, START);

        assertThat(rides.complete(id, "zone-norte", END, 30, true, new BigDecimal("4.50"), 85)).isFalse();
        assertThat(rides.find(id).orElseThrow().status()).isEqualTo(RideStatus.STARTING);
    }

    @Test
    void findStartingOnlyMatchesTheSameUserVehicleAndReservation() {
        String vehicle = "v-" + UUID.randomUUID();
        long withoutReservation = rides.insertStarting(vehicle, "u-1001", null, START);
        long withReservation = rides.insertStarting(vehicle, "u-1001", "res-7731", START);

        assertThat(rides.findStarting("u-1001", vehicle, null)).map(Ride::id).hasValue(withoutReservation);
        assertThat(rides.findStarting("u-1001", vehicle, "res-7731")).map(Ride::id).hasValue(withReservation);
        assertThat(rides.findStarting("u-2002", vehicle, null)).isEmpty();
        assertThat(rides.findStarting("u-1001", "v-" + UUID.randomUUID(), null)).isEmpty();
        assertThat(rides.findStarting("u-1001", vehicle, "res-0000")).isEmpty();
    }

    @Test
    void findStartingIgnoresRidesThatAlreadyHaveAnAnswer() {
        String vehicle = "v-" + UUID.randomUUID();
        rides.markInProgress(rides.insertStarting(vehicle, "u-1001", null, START), "zone-centro");
        rides.reject(rides.insertStarting(vehicle, "u-1001", null, START));

        assertThat(rides.findStarting("u-1001", vehicle, null)).isEmpty();
    }

    @Test
    void publicIdsRoundTrip() {
        assertThat(Ride.publicIdOf(5501)).isEqualTo("ride-5501");
        assertThat(Ride.parsePublicId("ride-5501")).hasValue(5501);
        assertThat(Ride.parsePublicId("res-5501")).isEmpty();
        assertThat(Ride.parsePublicId("ride-abc")).isEmpty();
        assertThat(Ride.parsePublicId(null)).isEmpty();
    }
}
