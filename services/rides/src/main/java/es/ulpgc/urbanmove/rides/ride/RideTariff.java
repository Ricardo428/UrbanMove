package es.ulpgc.urbanmove.rides.ride;

import es.ulpgc.urbanmove.rides.RidesProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Reglas de cobro y de batería simulada de un viaje. No guarda nada ni llama a nadie: dado el inicio y el fin,
 * calcula la duración, el coste preliminar y la batería que queda.
 */
@Component
public class RideTariff {

    private static final int FULL_BATTERY = 100;

    private final BigDecimal costPerMinute;
    private final int batteryDrainPerMinute;

    public RideTariff(RidesProperties properties) {
        this.costPerMinute = properties.tariff().costPerMinute();
        this.batteryDrainPerMinute = properties.tariff().batteryDrainPerMinute();
    }

    /** Minutos de viaje: cada minuto empezado cuenta entero, y como mínimo se cobra uno. */
    public int durationMinutes(Instant start, Instant end) {
        long seconds = Duration.between(start, end).toSeconds();
        long minutes = (seconds + 59) / 60;
        return (int) Math.max(1, minutes);
    }

    public BigDecimal preliminaryCost(int durationMinutes) {
        return costPerMinute.multiply(BigDecimal.valueOf(durationMinutes)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Hasta que exista telemetría, cada viaje empieza con la batería llena y gasta lo mismo por minuto. */
    public int batteryLevelPercent(int durationMinutes) {
        return Math.max(0, FULL_BATTERY - durationMinutes * batteryDrainPerMinute);
    }
}
