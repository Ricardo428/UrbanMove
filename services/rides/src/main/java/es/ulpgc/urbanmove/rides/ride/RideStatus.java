package es.ulpgc.urbanmove.rides.ride;

/**
 * Estados de un viaje. IN_PROGRESS y COMPLETED son los del contrato (docs/apis/api-rides.md).
 * STARTING y REJECTED son internos: el viaje se guarda antes de llamar a availability (ADR-002) y queda
 * REJECTED si availability no acepta el vehículo.
 */
public enum RideStatus {
    STARTING,
    IN_PROGRESS,
    COMPLETED,
    REJECTED
}
