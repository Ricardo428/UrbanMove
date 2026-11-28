package es.ulpgc.urbanmove.lambdas.relocation;

import java.time.Instant;
import java.util.List;

/**
 * La demanda de la ventana más reciente.
 *
 * @param windowStart y windowEnd la ventana que de verdad se usó; van tal cual en DemandHotspotDetected
 * @param dataUpTo    hasta cuándo está procesado el data mart; avanza en cada construcción aunque no haya eventos
 *                    nuevos, así que viejo significa data mart parado y no poca demanda. null si nunca se ha
 *                    construido
 * @param zones       solo las zonas y tipos con al menos una petición
 */
public record Demand(Instant windowStart, Instant windowEnd, Instant dataUpTo, List<ZoneDemand> zones) {

    public Demand {
        zones = zones == null ? List.of() : List.copyOf(zones);
    }
}
