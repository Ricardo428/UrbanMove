package es.ulpgc.urbanmove.relocation.order;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.relocation.availability.CountOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** El cálculo de origen y cantidad, sin Spring ni base de datos. Objetivo 6 en destino y mínimo 2 en origen. */
class RelocationPlanTest {

    private static final int TARGET = 6;
    private static final int MIN_LEFT = 2;

    @Test
    void theOriginIsTheOtherZoneWithMostAvailable() {
        var plan = decide("zone-centro", Map.of("zone-centro", 1, "zone-norte", 7, "zone-sur", 4));

        assertThat(plan).contains(new RelocationPlan("zone-norte", 5));
    }

    @Test
    void theQuantityIsLimitedByWhatTheDestinationMisses() {
        // Faltan 2 en el centro, aunque norte podría ceder 8.
        var plan = decide("zone-centro", Map.of("zone-centro", 4, "zone-norte", 10));

        assertThat(plan).contains(new RelocationPlan("zone-norte", 2));
    }

    @Test
    void theQuantityIsLimitedByWhatTheOriginCanGiveWithoutGoingBelowTheMinimum() {
        // Faltan 6 en el centro, pero norte solo puede ceder 3 para quedarse con 2.
        var plan = decide("zone-centro", Map.of("zone-norte", 5));

        assertThat(plan).contains(new RelocationPlan("zone-norte", 3));
    }

    @Test
    void aDestinationThatAlreadyReachesTheTargetNeedsNoOrder() {
        // El hotspot llegó tarde: entretanto se devolvieron recursos al centro.
        assertThat(decide("zone-centro", Map.of("zone-centro", 6, "zone-norte", 9))).isEmpty();
    }

    @Test
    void anOriginWithOnlyTheMinimumCannotGiveAnything() {
        assertThat(decide("zone-centro", Map.of("zone-centro", 0, "zone-norte", 2))).isEmpty();
    }

    @Test
    void withoutAnyOtherZoneThereIsNoOrder() {
        assertThat(decide("zone-centro", Map.of("zone-centro", 1))).isEmpty();
        assertThat(decide("zone-centro", Map.of())).isEmpty();
    }

    @Test
    void aTieIsResolvedByTheLowestZoneIdSoTheResultIsAlwaysTheSame() {
        var plan = decide("zone-centro", Map.of("zone-sur", 7, "zone-norte", 7, "zone-este", 7));

        // Las tres tienen 7: gana zone-este. Faltan 6 en el centro y este puede ceder 5.
        assertThat(plan).contains(new RelocationPlan("zone-este", 5));
    }

    private static java.util.Optional<RelocationPlan> decide(String destination, Map<String, Integer> counts) {
        return RelocationPlan.decide(destination, new CountOutcome.Counted(counts), TARGET, MIN_LEFT);
    }
}
