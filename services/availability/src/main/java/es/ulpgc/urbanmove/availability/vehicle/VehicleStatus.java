package es.ulpgc.urbanmove.availability.vehicle;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Los nueve estados del recurso (docs/states.md). */
public enum VehicleStatus {
    AVAILABLE,
    RESERVED,
    IN_USE,
    LOW_BATTERY,
    WITHDRAWN,
    CHARGING,
    REPAIRING,
    READY_FOR_SERVICE,
    RETIRED;

    /**
     * Transiciones que se piden con PUT /state (mantenimiento, recolocación y detección de batería baja).
     * Reservar, iniciar viaje y las liberaciones por evento tienen su propio camino y no están aquí.
     */
    private static final Map<VehicleStatus, Set<VehicleStatus>> MANUAL_TRANSITIONS = Map.of(
            AVAILABLE, EnumSet.of(LOW_BATTERY, WITHDRAWN),
            LOW_BATTERY, EnumSet.of(WITHDRAWN),
            WITHDRAWN, EnumSet.of(CHARGING, REPAIRING, READY_FOR_SERVICE, RETIRED),
            CHARGING, EnumSet.of(READY_FOR_SERVICE),
            REPAIRING, EnumSet.of(READY_FOR_SERVICE, RETIRED),
            READY_FOR_SERVICE, EnumSet.of(AVAILABLE));

    public boolean canChangeManuallyTo(VehicleStatus target) {
        return MANUAL_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
