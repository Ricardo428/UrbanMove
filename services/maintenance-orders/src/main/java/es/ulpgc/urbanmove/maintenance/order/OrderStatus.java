package es.ulpgc.urbanmove.maintenance.order;

import es.ulpgc.urbanmove.common.error.ApiException;

/** Estados de una orden de mantenimiento (docs/apis/api-maintenance-orders.md). COMPLETED es final. */
public enum OrderStatus {
    OPEN,
    WITHDRAWN,
    IN_PROGRESS,
    COMPLETED;

    /** Convierte el parámetro ?status= de la API; un valor desconocido es un 400. */
    public static OrderStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("status debe ser OPEN, WITHDRAWN, IN_PROGRESS o COMPLETED");
        }
    }
}
