package es.ulpgc.urbanmove.availability.vehicle;

import es.ulpgc.urbanmove.availability.AvailabilityProperties;
import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.events.VehicleType;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Única dueña del estado del recurso: valida toda transición según docs/states.md.
 * Reservar e iniciar viaje son síncronos e idempotentes por reservationId y rideId (ADR-002);
 * liberar llega por evento y solo se aplica si coincide quién tiene el recurso.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    private final VehicleStateRepository vehicles;
    private final int lowBatteryThreshold;

    public AvailabilityService(VehicleStateRepository vehicles, AvailabilityProperties properties) {
        this.vehicles = vehicles;
        this.lowBatteryThreshold = properties.lowBatteryThreshold();
    }

    public List<VehicleState> listAvailable(String zoneId, VehicleType type) {
        return vehicles.findAvailable(blankToNull(zoneId), type);
    }

    public VehicleState get(String vehicleId) {
        return vehicles.find(vehicleId).orElseThrow(() -> notFound(vehicleId));
    }

    @Transactional
    public VehicleState reserve(String vehicleId, String reservationId) {
        if (vehicles.reserve(vehicleId, reservationId)) {
            return get(vehicleId);
        }
        VehicleState current = get(vehicleId);
        // Reintento de la misma reserva (por ejemplo, tras un timeout): responde igual sin cambiar nada.
        if (current.status() == VehicleStatus.RESERVED && reservationId.equals(current.reservationId())) {
            return current;
        }
        throw ApiException.conflict("El vehículo " + vehicleId + " no está en estado AVAILABLE");
    }

    @Transactional
    public VehicleState start(String vehicleId, String rideId, String reservationId) {
        String reservation = blankToNull(reservationId);
        if (vehicles.start(vehicleId, rideId, reservation)) {
            return get(vehicleId);
        }
        VehicleState current = get(vehicleId);
        if (current.status() == VehicleStatus.IN_USE && rideId.equals(current.rideId())) {
            return current;
        }
        if (current.status() == VehicleStatus.RESERVED) {
            throw ApiException.conflict("El vehículo " + vehicleId + " está reservado por otra reserva");
        }
        throw ApiException.conflict("El vehículo " + vehicleId + " no está en estado AVAILABLE");
    }

    @Transactional
    public VehicleState changeState(String vehicleId, VehicleStatus target, String zoneId, String reason) {
        VehicleState current = get(vehicleId);
        String zone = blankToNull(zoneId);
        // Repetir la misma petición no es un error: el recurso ya está donde se pidió.
        if (current.status() == target && (zone == null || zone.equals(current.zoneId()))) {
            return current;
        }
        if (!current.status().canChangeManuallyTo(target)) {
            throw ApiException.conflict("Transición no permitida: " + current.status() + " → " + target);
        }
        if (!vehicles.changeStatus(vehicleId, current.status(), target, zone)) {
            throw ApiException.conflict("El vehículo " + vehicleId + " cambió de estado a la vez; vuelve a intentarlo");
        }
        log.info("{}: {} → {} ({})", vehicleId, current.status(), target, reason);
        return get(vehicleId);
    }

    // --- efectos de los eventos que consume ---

    @Transactional
    public void register(String vehicleId, VehicleType type, String zoneId) {
        if (!vehicles.insertAvailable(vehicleId, type, zoneId)) {
            log.info("VehiclePurchased de {} ignorado: el recurso ya existe", vehicleId);
        }
    }

    @Transactional
    public void release(String vehicleId, String reservationId) {
        if (!vehicles.release(vehicleId, reservationId)) {
            // Ya no lo tiene esa reserva: se usó para un viaje, se liberó antes o lo tiene otra.
            log.info("Liberación de {} por la reserva {} ignorada: la reserva ya no lo tiene", vehicleId,
                    reservationId);
        }
    }

    @Transactional
    public void finishRide(String vehicleId, String rideId, int batteryLevelPercent, String destinationZoneId) {
        VehicleStatus next = batteryLevelPercent < lowBatteryThreshold
                ? VehicleStatus.LOW_BATTERY
                : VehicleStatus.AVAILABLE;
        if (!vehicles.finishRide(vehicleId, rideId, next, Objects.requireNonNull(destinationZoneId))) {
            log.info("RideFinished de {} para el viaje {} ignorado: el recurso no está en ese viaje", vehicleId,
                    rideId);
        }
    }

    private static ApiException notFound(String vehicleId) {
        return ApiException.notFound("El vehículo " + vehicleId + " no existe");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
