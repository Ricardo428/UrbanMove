package es.ulpgc.urbanmove.inventory.vehicle;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.events.VehicleType;
import es.ulpgc.urbanmove.events.payload.VehiclePurchased;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleService {

    private final VehicleRepository vehicles;
    private final EventPublisher events;

    public VehicleService(VehicleRepository vehicles, EventPublisher events) {
        this.vehicles = vehicles;
        this.events = events;
    }

    /**
     * Da de alta el recurso y deja VehiclePurchased en la outbox, en la misma transacción: availability
     * solo conoce un recurso por ese evento, así que no puede haber recurso sin evento ni al revés.
     * zoneId no se guarda aquí: viaja en el evento y Availability es la dueña de la ubicación.
     */
    @Transactional
    public Vehicle register(VehicleType type, String serialNumber, String model, LocalDate purchaseDate,
            String zoneId) {
        String serial = serialNumber.trim();
        Vehicle vehicle = vehicles.insert(type, serial, model.trim(), purchaseDate)
                .orElseThrow(() -> ApiException.conflict("Ya existe un recurso con el número de serie " + serial));
        events.publish(new VehiclePurchased(vehicle.publicId(), vehicle.vehicleType(), vehicle.model(),
                vehicle.purchaseDate(), zoneId.trim()));
        return vehicle;
    }

    public Vehicle find(String vehicleId) {
        return Vehicle.parsePublicId(vehicleId)
                .flatMap(vehicles::find)
                .orElseThrow(() -> ApiException.notFound("El recurso " + vehicleId + " no existe"));
    }

    public List<Vehicle> list(VehicleType type, AdministrativeStatus status) {
        return vehicles.list(type, status);
    }
}
