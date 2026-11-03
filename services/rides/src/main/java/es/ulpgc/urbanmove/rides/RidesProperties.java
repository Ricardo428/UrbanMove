package es.ulpgc.urbanmove.rides;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración propia de rides (prefijo urbanmove.rides). Los valores por defecto están en application.yml.
 *
 * @param availability dónde está availability y cuánto se le espera al iniciar un viaje
 * @param reservations dónde está reservations y cuánto se le espera al comprobar una reserva
 * @param zones        dónde está zones y cuánto se le espera al comprobar la zona de destino
 * @param tariff       precio y consumo de batería simulado por minuto de viaje
 */
@ConfigurationProperties(prefix = "urbanmove.rides")
public record RidesProperties(Downstream availability, Downstream reservations, Downstream zones, Tariff tariff) {

    /** Un servicio al que rides llama de forma síncrona y cuánto se le espera. */
    public record Downstream(String url, Duration timeout) {
    }

    /**
     * @param costPerMinute           euros por minuto (o fracción) de viaje
     * @param batteryDrainPerMinute   puntos de batería que se gastan por minuto, hasta que exista telemetría
     */
    public record Tariff(BigDecimal costPerMinute, int batteryDrainPerMinute) {
    }
}
