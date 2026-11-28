package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.VehicleType;

/** Peticiones de una zona y un tipo de vehículo en la ventana, tal como las da analytics (api-analytics.md). */
public record ZoneDemand(String zoneId, VehicleType vehicleType, int requests) {
}
