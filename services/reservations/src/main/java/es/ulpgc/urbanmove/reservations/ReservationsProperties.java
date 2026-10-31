package es.ulpgc.urbanmove.reservations;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración propia de reservations (prefijo urbanmove.reservations).
 *
 * @param pendingTtl   tiempo que dura una reserva sin confirmar
 * @param confirmedTtl tiempo que dura una reserva desde que se confirma hasta que empieza el viaje
 * @param availability dónde está availability y cuánto se le espera
 */
@ConfigurationProperties(prefix = "urbanmove.reservations")
public record ReservationsProperties(
        @DefaultValue("5m") Duration pendingTtl,
        @DefaultValue("15m") Duration confirmedTtl,
        @DefaultValue Availability availability) {

    public record Availability(
            @DefaultValue("http://localhost:8083") String url,
            @DefaultValue("3s") Duration timeout) {
    }
}
