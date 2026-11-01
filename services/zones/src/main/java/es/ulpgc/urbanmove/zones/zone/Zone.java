package es.ulpgc.urbanmove.zones.zone;

/** Una zona de operación. Una zona inactiva no acepta nuevos aparcamientos, pero conserva los vehículos que tiene. */
public record Zone(
        String zoneId,
        String name,
        boolean parkingAllowed,
        boolean active,
        double centerLat,
        double centerLon,
        int radiusMeters) {
}
