package es.ulpgc.urbanmove.inventory.vehicle;

import es.ulpgc.urbanmove.events.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contrato en docs/apis/api-inventory.md. */
@RestController
@RequestMapping("/api/v1/inventory/vehicles")
public class VehicleController {

    private final VehicleService vehicles;

    public VehicleController(VehicleService vehicles) {
        this.vehicles = vehicles;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public VehicleView register(@Valid @RequestBody RegisterRequest request) {
        return VehicleView.of(vehicles.register(request.vehicleType(), request.serialNumber(), request.model(),
                request.purchaseDate(), request.zoneId()));
    }

    /** Cualquier usuario autenticado puede consultar un recurso. */
    @GetMapping("/{vehicleId}")
    public VehicleView get(@PathVariable String vehicleId) {
        return VehicleView.of(vehicles.find(vehicleId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public List<VehicleView> list(@RequestParam(required = false) VehicleType vehicleType,
            @RequestParam(required = false) AdministrativeStatus administrativeStatus) {
        return vehicles.list(vehicleType, administrativeStatus).stream().map(VehicleView::of).toList();
    }

    public record RegisterRequest(
            @NotNull VehicleType vehicleType,
            @NotBlank @Size(max = 64) String serialNumber,
            @NotBlank @Size(max = 128) String model,
            @NotNull @PastOrPresent LocalDate purchaseDate,
            @NotBlank @Size(max = 64) String zoneId) {
    }

    public record VehicleView(
            String vehicleId,
            VehicleType vehicleType,
            String serialNumber,
            String model,
            LocalDate purchaseDate,
            AdministrativeStatus administrativeStatus) {

        static VehicleView of(Vehicle vehicle) {
            return new VehicleView(vehicle.publicId(), vehicle.vehicleType(), vehicle.serialNumber(),
                    vehicle.model(), vehicle.purchaseDate(), vehicle.administrativeStatus());
        }
    }
}
