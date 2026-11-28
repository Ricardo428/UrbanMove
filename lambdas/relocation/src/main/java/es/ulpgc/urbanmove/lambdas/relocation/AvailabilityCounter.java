package es.ulpgc.urbanmove.lambdas.relocation;

import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

import es.ulpgc.urbanmove.events.VehicleType;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Cuenta los vehículos AVAILABLE de ahora mismo por tipo y zona, con GET /api/v1/availability?type=
 * (docs/apis/api-availability.md, sección 1). Es la misma consulta que hace relocation-orders.
 */
public final class AvailabilityCounter implements AvailableCountsSource {

    private final ServiceHttp availability;

    public AvailabilityCounter(ServiceHttp availability) {
        this.availability = availability;
    }

    /**
     * Una llamada por tipo. Lanza UpstreamException si alguna falla: con medio recuento, el tipo que falta
     * contaría 0 en todas las zonas y saldrían hotspots falsos.
     */
    @Override
    public AvailableCounts count(String correlationId) {
        Map<VehicleType, Map<String, Integer>> byTypeAndZone = new EnumMap<>(VehicleType.class);
        for (VehicleType type : VehicleType.values()) {
            AvailableVehicle[] vehicles = availability.get("/api/v1/availability?type=" + type.name(),
                    AvailableVehicle[].class, correlationId);
            byTypeAndZone.put(type, Arrays.stream(vehicles)
                    .collect(groupingBy(AvailableVehicle::zoneId, collectingAndThen(counting(), Long::intValue))));
        }
        return new AvailableCounts(byTypeAndZone);
    }

    /** Un elemento de la lista de Availability; solo interesa la zona. */
    record AvailableVehicle(String vehicleId, String zoneId) {
    }
}
