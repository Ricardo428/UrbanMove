package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.VehicleType;

/** Una zona y un tipo con más demanda que vehículos disponibles: se publicará como DemandHotspotDetected. */
public record Hotspot(String zoneId, VehicleType vehicleType, int availableCount, int requests) {
}
