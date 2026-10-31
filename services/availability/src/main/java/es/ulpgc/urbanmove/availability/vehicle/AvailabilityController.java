package es.ulpgc.urbanmove.availability.vehicle;

import com.fasterxml.jackson.annotation.JsonInclude;
import es.ulpgc.urbanmove.events.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contrato en docs/apis/api-availability.md. reserve y start son internos: los llaman reservations y rides. */
@RestController
@RequestMapping("/api/v1/availability")
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    /** Un type que no sea BIKE ni SCOOTER no se puede convertir y da 400. */
    @GetMapping
    public List<VehicleView> listAvailable(@RequestParam(required = false) String zoneId,
            @RequestParam(required = false) VehicleType type) {
        return availability.listAvailable(zoneId, type).stream().map(VehicleView::of).toList();
    }

    @GetMapping("/{vehicleId}")
    public VehicleView get(@PathVariable String vehicleId) {
        return VehicleView.of(availability.get(vehicleId));
    }

    @PostMapping("/{vehicleId}/reserve")
    @PreAuthorize("hasRole('ADMIN')")
    public ReserveResponse reserve(@PathVariable String vehicleId, @Valid @RequestBody ReserveRequest request) {
        VehicleState state = availability.reserve(vehicleId, request.reservationId());
        return new ReserveResponse(state.vehicleId(), state.status(), state.reservationId(), state.zoneId());
    }

    @PostMapping("/{vehicleId}/start")
    @PreAuthorize("hasRole('ADMIN')")
    public StateResponse start(@PathVariable String vehicleId, @Valid @RequestBody StartRequest request) {
        return StateResponse.of(availability.start(vehicleId, request.rideId(), request.reservationId()));
    }

    /** Un state que no sea uno de los nueve estados no se puede leer y da 400. */
    @PutMapping("/{vehicleId}/state")
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public StateResponse changeState(@PathVariable String vehicleId,
            @Valid @RequestBody ChangeStateRequest request) {
        return StateResponse.of(
                availability.changeState(vehicleId, request.state(), request.zoneId(), request.reason()));
    }

    public record ReserveRequest(@NotBlank @Size(max = 64) String reservationId) {
    }

    public record StartRequest(@NotBlank @Size(max = 64) String rideId, @Size(max = 64) String reservationId) {
    }

    /** reason solo queda en el log: el detalle de la orden lo guarda el servicio que pide el cambio. */
    public record ChangeStateRequest(@NotNull VehicleStatus state, @Size(max = 255) String reason,
            @Size(max = 64) String zoneId) {
    }

    /** reservationId solo aparece mientras el recurso está RESERVED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VehicleView(String vehicleId, VehicleType vehicleType, String zoneId, VehicleStatus status,
            String reservationId) {

        static VehicleView of(VehicleState state) {
            return new VehicleView(state.vehicleId(), state.vehicleType(), state.zoneId(), state.status(),
                    state.reservationId());
        }
    }

    public record ReserveResponse(String vehicleId, VehicleStatus status, String reservationId, String zoneId) {
    }

    public record StateResponse(String vehicleId, VehicleStatus status, String zoneId) {

        static StateResponse of(VehicleState state) {
            return new StateResponse(state.vehicleId(), state.status(), state.zoneId());
        }
    }
}
