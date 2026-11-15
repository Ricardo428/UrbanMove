package es.ulpgc.urbanmove.lambdas.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.EventJson;
import es.ulpgc.urbanmove.events.payload.UserDeactivated;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Prueba la lógica de la ingesta con un almacén en memoria en lugar de S3. */
class IngestJobTest {

    private static final Instant WHEN = Instant.parse("2026-11-14T23:59:30Z");

    private final Map<String, String> objects = new ConcurrentHashMap<>();
    private final Set<String> failingKeys = ConcurrentHashMap.newKeySet();
    private final List<String> log = new ArrayList<>();

    private final IngestJob job = new IngestJob((key, json) -> {
        if (failingKeys.stream().anyMatch(key::contains)) {
            throw new IllegalStateException("S3 no responde");
        }
        objects.put(key, json);
    });

    @Test
    void storesTheEventUnchangedUnderItsPartition() {
        UUID id = UUID.randomUUID();
        String body = eventJson(id, WHEN, "UserDeactivated", "profiles-service");

        IngestJob.Result result = job.run(List.of(new IngestJob.Message("m-1", body)), log::add);

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.failedIds()).isEmpty();
        assertThat(objects).containsOnlyKeys(
                "raw/event_date=2026-11-14/event_type=UserDeactivated/source=profiles-service/" + id + ".json");
        // Raw es una copia fiel: el cuerpo se guarda tal como llegó, sin volver a serializarlo.
        assertThat(objects.values()).containsExactly(body);
    }

    @Test
    void thePartitionDateIsTheEventDateNotTheIngestionDate() {
        String lateEvent = eventJson(UUID.randomUUID(), Instant.parse("2025-01-02T03:04:05Z"), "RideStarted", "rides");

        job.run(List.of(new IngestJob.Message("m-1", lateEvent)), log::add);

        assertThat(objects.keySet()).singleElement().asString().startsWith("raw/event_date=2025-01-02/");
    }

    @Test
    void aDuplicateDeliveryOverwritesTheSameObjectInsteadOfCreatingAnother() {
        String body = eventJson(UUID.randomUUID(), WHEN, "RideFinished", "rides");

        job.run(List.of(new IngestJob.Message("m-1", body), new IngestJob.Message("m-2", body)), log::add);
        job.run(List.of(new IngestJob.Message("m-3", body)), log::add);

        assertThat(objects).hasSize(1);
    }

    @Test
    void differentEventsOfTheSameDayGoToTheirOwnTypeAndSource() {
        job.run(List.of(
                new IngestJob.Message("m-1", eventJson(UUID.randomUUID(), WHEN, "RideStarted", "rides")),
                new IngestJob.Message("m-2", eventJson(UUID.randomUUID(), WHEN, "RideFinished", "rides")),
                new IngestJob.Message("m-3", eventJson(UUID.randomUUID(), WHEN, "VehicleReserved", "reservations"))),
                log::add);

        assertThat(objects.keySet()).anyMatch(key -> key.contains("/event_type=RideStarted/source=rides/"))
                .anyMatch(key -> key.contains("/event_type=RideFinished/source=rides/"))
                .anyMatch(key -> key.contains("/event_type=VehicleReserved/source=reservations/"))
                .hasSize(3);
    }

    @Test
    void valuesTakenFromTheEventCannotAlterThePath() {
        String body = eventJson(UUID.randomUUID(), WHEN, "Ride/../Started", "../otro bucket");

        job.run(List.of(new IngestJob.Message("m-1", body)), log::add);

        assertThat(objects.keySet()).singleElement().asString()
                .startsWith("raw/event_date=2026-11-14/event_type=Ride_.._Started/source=.._otro_bucket/")
                .doesNotContain("//");
    }

    @Test
    void aMessageThatIsNotAnEventFailsAloneAndDoesNotStopTheRest() {
        String good = eventJson(UUID.randomUUID(), WHEN, "RideStarted", "rides");

        IngestJob.Result result = job.run(List.of(
                new IngestJob.Message("m-1", "esto no es json"),
                new IngestJob.Message("m-2", good),
                new IngestJob.Message("m-3", "{\"eventId\":\"" + UUID.randomUUID() + "\"}")),
                log::add);

        assertThat(result.stored()).isEqualTo(1);
        assertThat(result.failedIds()).containsExactly("m-1", "m-3");
        assertThat(objects).hasSize(1);
    }

    @Test
    void whenTheStoreFailsOnlyThatMessageIsReportedSoSqsRedeliversJustThatOne() {
        UUID broken = UUID.randomUUID();
        failingKeys.add(broken.toString());

        IngestJob.Result result = job.run(List.of(
                new IngestJob.Message("m-1", eventJson(UUID.randomUUID(), WHEN, "RideStarted", "rides")),
                new IngestJob.Message("m-2", eventJson(broken, WHEN, "RideStarted", "rides")),
                new IngestJob.Message("m-3", eventJson(UUID.randomUUID(), WHEN, "RideStarted", "rides"))),
                log::add);

        assertThat(result.stored()).isEqualTo(2);
        assertThat(result.failedIds()).containsExactly("m-2");
    }

    @Test
    void theLogNeverContainsTheMessageBody() {
        job.run(List.of(new IngestJob.Message("m-1", "{\"secreto\": \"u-1001 dato personal\"}")), log::add);

        assertThat(String.join("\n", log)).doesNotContain("dato personal");
    }

    private static String eventJson(UUID id, Instant timestamp, String type, String source) {
        EventEnvelope event = new EventEnvelope(id, timestamp, source, type, "v1", "c-test",
                EventJson.mapper().valueToTree(new UserDeactivated("u-1001", timestamp)));
        return EventJson.write(event);
    }
}
