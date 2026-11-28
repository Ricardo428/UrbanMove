package es.ulpgc.urbanmove.lambdas.relocation;

import java.util.List;

/**
 * La regla del enunciado (sección 8.3): hay hotspot si una zona tiene más de {@code requestsAbove} peticiones en la
 * ventana y menos de {@code availableBelow} vehículos disponibles del mismo tipo. Las dos comparaciones son
 * estrictas, como en el enunciado ("más de diez", "menos de tres").
 *
 * Solo decide: no sabe de dónde salen los datos ni qué se hace con el resultado.
 */
public record HotspotRule(int requestsAbove, int availableBelow) {

    public HotspotRule {
        if (requestsAbove < 0 || availableBelow < 1) {
            throw new IllegalArgumentException(
                    "Umbrales no válidos: requestsAbove=" + requestsAbove + ", availableBelow=" + availableBelow);
        }
    }

    /**
     * Recorre la demanda, no los disponibles: una zona con 0 disponibles no sale en la lista de Availability,
     * pero sí en la de analytics si ha tenido peticiones.
     */
    public List<Hotspot> detect(List<ZoneDemand> demand, AvailableCounts available) {
        return demand.stream()
                .filter(zone -> zone.requests() > requestsAbove)
                .map(zone -> new Hotspot(zone.zoneId(), zone.vehicleType(),
                        available.in(zone.zoneId(), zone.vehicleType()), zone.requests()))
                .filter(hotspot -> hotspot.availableCount() < availableBelow)
                .toList();
    }
}
