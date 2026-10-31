package es.ulpgc.urbanmove.availability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración propia de availability (prefijo urbanmove.availability).
 *
 * @param lowBatteryThreshold porcentaje por debajo del cual un viaje que termina deja el recurso en LOW_BATTERY
 */
@ConfigurationProperties(prefix = "urbanmove.availability")
public record AvailabilityProperties(@DefaultValue("20") int lowBatteryThreshold) {
}
