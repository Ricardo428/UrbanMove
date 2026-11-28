package es.ulpgc.urbanmove.lambdas.relocation;

/** De dónde salen los vehículos disponibles. En producción, AvailabilityCounter; en los tests, un recuento fijo. */
@FunctionalInterface
public interface AvailableCountsSource {

    /** Lanza UpstreamException si no se puede obtener. */
    AvailableCounts count(String correlationId);
}
