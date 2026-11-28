package es.ulpgc.urbanmove.lambdas.relocation;

import static es.ulpgc.urbanmove.events.VehicleType.BIKE;
import static es.ulpgc.urbanmove.events.VehicleType.SCOOTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Prueba el orquestador con demanda, disponibles y bus falsos: sin HTTP ni LocalStack. */
class RelocationJobTest {

    private static final Instant NOW = Instant.parse("2026-10-13T16:01:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-10-13T15:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-13T16:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<EventEnvelope> published = new ArrayList<>();
    private final AtomicInteger availabilityCalls = new AtomicInteger();

    /** Dos patinetes en el centro, ninguna bici en ningún sitio. */
    private final AvailableCountsSource available = correlationId -> {
        availabilityCalls.incrementAndGet();
        return new AvailableCounts(Map.of(SCOOTER, Map.of("zone-centro", 2)));
    };

    @Test
    void publishesOneDemandHotspotDetectedPerHotspot() {
        Demand demand = upToDate(List.of(
                new ZoneDemand("zone-centro", SCOOTER, 14),
                new ZoneDemand("zone-norte", BIKE, 12),
                new ZoneDemand("zone-sur", BIKE, 3)));

        RelocationJob.Result result = job(demand, published::add).run(message -> { });

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.failed()).isEmpty();
        assertThat(published).extracting(event -> event.payloadAs(DemandHotspotDetected.class)).containsExactly(
                new DemandHotspotDetected("zone-centro", SCOOTER, 2, 14, WINDOW_START, WINDOW_END),
                new DemandHotspotDetected("zone-norte", BIKE, 0, 12, WINDOW_START, WINDOW_END));
    }

    @Test
    void theEnvelopeCarriesTheSourceAndOneCorrelationIdPerExecution() {
        Demand demand = upToDate(List.of(
                new ZoneDemand("zone-centro", SCOOTER, 14),
                new ZoneDemand("zone-norte", BIKE, 12)));

        job(demand, published::add).run(message -> { });

        assertThat(published).allSatisfy(event -> {
            assertThat(event.source()).isEqualTo("lambda-relocation");
            assertThat(event.type()).isEqualTo("DemandHotspotDetected");
            assertThat(event.correlationId()).startsWith("lambda-relocation-");
        });
        assertThat(published).extracting(EventEnvelope::correlationId).containsOnly(published.get(0).correlationId());
        assertThat(published).extracting(EventEnvelope::eventId).doesNotHaveDuplicates();
    }

    @Test
    void withoutHotspotsNothingIsPublished() {
        Demand demand = upToDate(List.of(new ZoneDemand("zone-centro", SCOOTER, 4)));

        RelocationJob.Result result = job(demand, published::add).run(message -> { });

        assertThat(result.published()).isZero();
        assertThat(published).isEmpty();
    }

    @Test
    void anOutdatedDataMartStopsTheExecutionBeforeAskingAvailability() {
        Demand stale = new Demand(WINDOW_START, WINDOW_END, NOW.minus(Duration.ofMinutes(31)),
                List.of(new ZoneDemand("zone-centro", SCOOTER, 14)));

        assertThatThrownBy(() -> job(stale, published::add).run(message -> { }))
                .isInstanceOf(RelocationJob.StaleDemandException.class)
                .hasMessageContaining("31 min de retraso");
        assertThat(availabilityCalls).hasValue(0);
        assertThat(published).isEmpty();
    }

    @Test
    void aDataMartThatWasNeverBuiltStopsTheExecution() {
        Demand neverBuilt = new Demand(WINDOW_START, WINDOW_END, null, List.of());

        assertThatThrownBy(() -> job(neverBuilt, published::add).run(message -> { }))
                .isInstanceOf(RelocationJob.StaleDemandException.class)
                .hasMessageContaining("no se ha construido nunca");
    }

    /** De madrugada: el data mart está al día pero nadie ha reservado. No es un error, es poca demanda. */
    @Test
    void anUpToDateDataMartWithoutDemandIsNotAnError() {
        Demand quietNight = upToDate(List.of());

        RelocationJob.Result result = job(quietNight, published::add).run(message -> { });

        assertThat(result.zonesWithDemand()).isZero();
        assertThat(result.failed()).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void dataExactlyAtTheLimitIsStillUsed() {
        Demand demand = new Demand(WINDOW_START, WINDOW_END, NOW.minus(Duration.ofMinutes(30)),
                List.of(new ZoneDemand("zone-centro", SCOOTER, 14)));

        assertThat(job(demand, published::add).run(message -> { }).published()).isEqualTo(1);
    }

    @Test
    void ifAnalyticsFailsNothingIsPublished() {
        DemandSource down = correlationId -> {
            throw new UpstreamException("analytics no responde");
        };
        RelocationJob job = new RelocationJob(down, available, new HotspotRule(10, 3), published::add, clock,
                Duration.ofMinutes(30));

        assertThatThrownBy(() -> job.run(message -> { })).isInstanceOf(UpstreamException.class);
        assertThat(published).isEmpty();
    }

    @Test
    void ifOnePublishFailsTheOthersAreStillPublished() {
        Demand demand = upToDate(List.of(
                new ZoneDemand("zone-centro", SCOOTER, 14),
                new ZoneDemand("zone-norte", BIKE, 12)));
        EventBus failsForTheCentre = event -> {
            if (event.payloadAs(DemandHotspotDetected.class).zoneId().equals("zone-centro")) {
                throw new IllegalStateException("SNS no responde");
            }
            published.add(event);
        };

        RelocationJob.Result result = job(demand, failsForTheCentre).run(message -> { });

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).singleElement().asString().contains("zone-centro/SCOOTER", "SNS no responde");
        assertThat(published).extracting(event -> event.payloadAs(DemandHotspotDetected.class).zoneId())
                .containsExactly("zone-norte");
    }

    @Test
    void aWrongConfigurationStopsTheLambdaWithAClearMessage() {
        assertThatThrownBy(() -> RelocationJob.fromEnvironment(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("URBANMOVE_JWT_SECRET");
        assertThatThrownBy(() -> RelocationJob.fromEnvironment(Map.of(
                "URBANMOVE_JWT_SECRET", ServiceClientsTest.SECRET,
                "ANALYTICS_URL", "http://analytics:8090",
                "AVAILABILITY_URL", "http://availability:8083",
                "REQUESTS_ABOVE", "diez")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REQUESTS_ABOVE");
        assertThatThrownBy(() -> RelocationJob.fromEnvironment(Map.of(
                "URBANMOVE_JWT_SECRET", ServiceClientsTest.SECRET,
                "ANALYTICS_URL", "http://analytics:8090",
                "AVAILABILITY_URL", "http://availability:8083")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOPIC_ARN");
    }

    // --- utilidades ---

    private RelocationJob job(Demand demand, EventBus bus) {
        return new RelocationJob(correlationId -> demand, available, new HotspotRule(10, 3), bus, clock,
                Duration.ofMinutes(30));
    }

    private static Demand upToDate(List<ZoneDemand> zones) {
        return new Demand(WINDOW_START, WINDOW_END, NOW.minus(Duration.ofMinutes(2)), zones);
    }
}
