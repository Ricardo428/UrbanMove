package es.ulpgc.urbanmove.rides.ride;

import static org.assertj.core.api.Assertions.assertThat;

import es.ulpgc.urbanmove.rides.RidesProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Reglas de cobro y batería, sin Spring ni base de datos. */
class RideTariffTest {

    private static final Instant START = Instant.parse("2026-10-13T10:30:00Z");

    private final RideTariff tariff = tariff("0.15", 1);

    @Test
    void anExactNumberOfMinutesIsKeptAsIs() {
        assertThat(tariff.durationMinutes(START, START.plus(Duration.ofMinutes(30)))).isEqualTo(30);
    }

    @Test
    void aStartedMinuteCountsAsAWholeOne() {
        assertThat(tariff.durationMinutes(START, START.plus(Duration.ofMinutes(30).plusSeconds(1)))).isEqualTo(31);
    }

    @Test
    void theShortestRideCountsAsOneMinute() {
        assertThat(tariff.durationMinutes(START, START)).isEqualTo(1);
        assertThat(tariff.durationMinutes(START, START.plusSeconds(5))).isEqualTo(1);
    }

    @Test
    void theCostIsTheMinutesTimesThePriceWithTwoDecimals() {
        assertThat(tariff.preliminaryCost(30)).isEqualTo(new BigDecimal("4.50"));
        assertThat(tariff.preliminaryCost(1)).isEqualTo(new BigDecimal("0.15"));
        assertThat(tariff("0.333", 1).preliminaryCost(1)).isEqualTo(new BigDecimal("0.33"));
    }

    @Test
    void theBatteryDropsWithTheMinutesAndNeverGoesBelowZero() {
        assertThat(tariff.batteryLevelPercent(30)).isEqualTo(70);
        assertThat(tariff.batteryLevelPercent(150)).isZero();
        assertThat(tariff("0.15", 50).batteryLevelPercent(2)).isZero();
        assertThat(tariff("0.15", 50).batteryLevelPercent(1)).isEqualTo(50);
    }

    private static RideTariff tariff(String costPerMinute, int batteryDrainPerMinute) {
        RidesProperties.Tariff tariff = new RidesProperties.Tariff(new BigDecimal(costPerMinute), batteryDrainPerMinute);
        return new RideTariff(new RidesProperties(null, null, null, tariff));
    }
}
