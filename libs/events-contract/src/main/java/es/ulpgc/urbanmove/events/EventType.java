package es.ulpgc.urbanmove.events;

import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCompleted;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCreated;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.events.payload.UserRegistered;
import es.ulpgc.urbanmove.events.payload.VehicleBatteryLow;
import es.ulpgc.urbanmove.events.payload.VehiclePurchased;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.events.payload.VehicleReturnedToService;
import es.ulpgc.urbanmove.events.payload.VehicleWithdrawnForMaintenance;
import java.util.Arrays;
import java.util.Optional;

/**
 * Los eventos de dominio del sistema: nombre en el bus (campo "type"), clase del payload y versión del esquema.
 * Un cambio incompatible en un payload sube su versión.
 */
public enum EventType {

    USER_REGISTERED("UserRegistered", UserRegistered.class),
    VEHICLE_PURCHASED("VehiclePurchased", VehiclePurchased.class),
    VEHICLE_RESERVED("VehicleReserved", VehicleReserved.class),
    RESERVATION_CANCELLED("ReservationCancelled", ReservationCancelled.class),
    RESERVATION_EXPIRED("ReservationExpired", ReservationExpired.class),
    RIDE_STARTED("RideStarted", RideStarted.class),
    RIDE_FINISHED("RideFinished", RideFinished.class),
    VEHICLE_BATTERY_LOW("VehicleBatteryLow", VehicleBatteryLow.class),
    VEHICLE_WITHDRAWN_FOR_MAINTENANCE("VehicleWithdrawnForMaintenance", VehicleWithdrawnForMaintenance.class),
    VEHICLE_RETURNED_TO_SERVICE("VehicleReturnedToService", VehicleReturnedToService.class),
    DEMAND_HOTSPOT_DETECTED("DemandHotspotDetected", DemandHotspotDetected.class),
    RELOCATION_ORDER_CREATED("RelocationOrderCreated", RelocationOrderCreated.class),
    RELOCATION_ORDER_COMPLETED("RelocationOrderCompleted", RelocationOrderCompleted.class);

    private final String wireName;
    private final Class<? extends EventPayload> payloadClass;

    EventType(String wireName, Class<? extends EventPayload> payloadClass) {
        this.wireName = wireName;
        this.payloadClass = payloadClass;
    }

    /** Nombre del evento tal y como viaja en el campo "type", por ejemplo UserRegistered. */
    public String wireName() {
        return wireName;
    }

    public Class<? extends EventPayload> payloadClass() {
        return payloadClass;
    }

    public String version() {
        return "v1";
    }

    /** Busca por el nombre del campo "type". Vacío si el evento no es de este contrato. */
    public static Optional<EventType> fromWireName(String wireName) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(wireName)).findFirst();
    }

    public static EventType of(Class<? extends EventPayload> payloadClass) {
        return Arrays.stream(values())
                .filter(type -> type.payloadClass.equals(payloadClass))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        payloadClass.getName() + " no está registrado en EventType"));
    }
}
