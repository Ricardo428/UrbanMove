package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import es.ulpgc.urbanmove.events.VehicleType;
import java.time.LocalDate;

/** Incorporación de un recurso nuevo. zoneId es la zona en la que se pone en servicio. */
public record VehiclePurchased(
        String vehicleId,
        VehicleType vehicleType,
        String model,
        LocalDate purchaseDate,
        String zoneId) implements EventPayload {
}
