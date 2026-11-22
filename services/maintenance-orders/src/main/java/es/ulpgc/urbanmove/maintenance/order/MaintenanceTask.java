package es.ulpgc.urbanmove.maintenance.order;

import es.ulpgc.urbanmove.common.error.ApiException;

/**
 * Tarea que registra el operario al empezar. Cada una indica el estado que se pide a availability:
 * states.md no tiene un estado propio para la revisión, así que REVIEW usa REPAIRING.
 */
public enum MaintenanceTask {
    RECHARGE("CHARGING"),
    REPAIR("REPAIRING"),
    REVIEW("REPAIRING");

    private final String vehicleState;

    MaintenanceTask(String vehicleState) {
        this.vehicleState = vehicleState;
    }

    /** Estado del recurso en availability mientras dura la tarea. */
    public String vehicleState() {
        return vehicleState;
    }

    /** Convierte el campo task de la petición; falta o valor desconocido es un 400. */
    public static MaintenanceTask parse(String value) {
        if (value == null) {
            throw ApiException.badRequest("task es obligatorio: RECHARGE, REPAIR o REVIEW");
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("task debe ser RECHARGE, REPAIR o REVIEW");
        }
    }
}
