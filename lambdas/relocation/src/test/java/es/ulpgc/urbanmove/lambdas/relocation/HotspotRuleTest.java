package es.ulpgc.urbanmove.lambdas.relocation;

import static es.ulpgc.urbanmove.events.VehicleType.BIKE;
import static es.ulpgc.urbanmove.events.VehicleType.SCOOTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HotspotRuleTest {

    /** Los umbrales del enunciado: más de 10 peticiones y menos de 3 disponibles. */
    private final HotspotRule rule = new HotspotRule(10, 3);

    @Test
    void aZoneWithHighDemandAndFewVehiclesIsAHotspot() {
        AvailableCounts available = new AvailableCounts(Map.of(SCOOTER, Map.of("zone-centro", 2)));

        List<Hotspot> hotspots = rule.detect(List.of(new ZoneDemand("zone-centro", SCOOTER, 14)), available);

        assertThat(hotspots).containsExactly(new Hotspot("zone-centro", SCOOTER, 2, 14));
    }

    @Test
    void aZoneMissingFromAvailabilityCountsAsZeroAvailable() {
        AvailableCounts noneAvailable = new AvailableCounts(Map.of());

        List<Hotspot> hotspots = rule.detect(List.of(new ZoneDemand("zone-centro", SCOOTER, 11)), noneAvailable);

        assertThat(hotspots).containsExactly(new Hotspot("zone-centro", SCOOTER, 0, 11));
    }

    @Test
    void bothLimitsAreStrict() {
        AvailableCounts available = new AvailableCounts(Map.of(BIKE, Map.of("zone-norte", 2, "zone-sur", 3)));

        List<Hotspot> hotspots = rule.detect(List.of(
                new ZoneDemand("zone-norte", BIKE, 10),   // 10 peticiones no es "más de 10"
                new ZoneDemand("zone-sur", BIKE, 20)),    // 3 disponibles no es "menos de 3"
                available);

        assertThat(hotspots).isEmpty();
    }

    @Test
    void vehiclesOfAnotherTypeDoNotCover() {
        AvailableCounts onlyBikes = new AvailableCounts(Map.of(BIKE, Map.of("zone-centro", 15)));

        List<Hotspot> hotspots = rule.detect(List.of(new ZoneDemand("zone-centro", SCOOTER, 12)), onlyBikes);

        assertThat(hotspots).containsExactly(new Hotspot("zone-centro", SCOOTER, 0, 12));
    }

    @Test
    void eachZoneAndTypeIsDecidedOnItsOwn() {
        AvailableCounts available = new AvailableCounts(Map.of(
                SCOOTER, Map.of("zone-centro", 1, "zone-norte", 8),
                BIKE, Map.of("zone-centro", 0)));

        List<Hotspot> hotspots = rule.detect(List.of(
                new ZoneDemand("zone-centro", SCOOTER, 14),
                new ZoneDemand("zone-norte", SCOOTER, 30),
                new ZoneDemand("zone-centro", BIKE, 4)),
                available);

        assertThat(hotspots).containsExactly(new Hotspot("zone-centro", SCOOTER, 1, 14));
    }

    @Test
    void invalidThresholdsAreRejected() {
        assertThatThrownBy(() -> new HotspotRule(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HotspotRule(-1, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
