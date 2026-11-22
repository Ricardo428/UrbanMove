package es.ulpgc.urbanmove.relocation.order;

import es.ulpgc.urbanmove.common.error.ApiException;

/** Estados de una orden de recolocación (docs/apis/api-relocation-orders.md). COMPLETED y CANCELLED son finales. */
public enum OrderStatus {
    OPEN,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /** Convierte el parámetro ?status= de la API; un valor desconocido es un 400. */
    public static OrderStatus parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("status debe ser OPEN, IN_PROGRESS, COMPLETED o CANCELLED");
        }
    }
}
