package es.ulpgc.urbanmove.relocation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración propia de relocation-orders (prefijo urbanmove.relocation, docs/apis/api-relocation-orders.md).
 *
 * @param targetAvailable recursos disponibles que se quieren tener en la zona destino
 * @param minLeftInOrigin recursos disponibles que deben quedar en la zona origen
 * @param availability    dónde está availability y cuánto se le espera
 */
@ConfigurationProperties(prefix = "urbanmove.relocation")
public record RelocationProperties(
        @DefaultValue("6") int targetAvailable,
        @DefaultValue("2") int minLeftInOrigin,
        @DefaultValue Availability availability) {

    public record Availability(
            @DefaultValue("http://localhost:8083") String url,
            @DefaultValue("3s") Duration timeout) {
    }
}
