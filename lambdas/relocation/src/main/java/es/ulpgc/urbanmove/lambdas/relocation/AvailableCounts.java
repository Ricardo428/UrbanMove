package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.VehicleType;
import java.util.Map;

/**
 * Vehículos AVAILABLE ahora mismo, por tipo y por zona.
 *
 * Availability solo lista los vehículos disponibles, así que una zona sin ninguno no aparece en su respuesta.
 * Por eso una zona que no está aquí cuenta como 0, no como "desconocida": es justo la que más interesa (ADR-012).
 */
public record AvailableCounts(Map<VehicleType, Map<String, Integer>> byTypeAndZone) {

    public AvailableCounts {
        byTypeAndZone = Map.copyOf(byTypeAndZone);
    }

    public int in(String zoneId, VehicleType type) {
        return byTypeAndZone.getOrDefault(type, Map.of()).getOrDefault(zoneId, 0);
    }
}
