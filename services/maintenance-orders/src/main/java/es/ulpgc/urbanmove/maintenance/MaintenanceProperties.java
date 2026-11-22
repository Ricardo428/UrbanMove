package es.ulpgc.urbanmove.maintenance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración propia de maintenance-orders (prefijo urbanmove.maintenance).
 *
 * @param availability dónde está availability y cuánto se le espera
 */
@ConfigurationProperties(prefix = "urbanmove.maintenance")
public record MaintenanceProperties(@DefaultValue Availability availability) {

    public record Availability(
            @DefaultValue("http://localhost:8083") String url,
            @DefaultValue("3s") Duration timeout) {
    }
}
