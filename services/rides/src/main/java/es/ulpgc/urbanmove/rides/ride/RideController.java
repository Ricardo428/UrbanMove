package es.ulpgc.urbanmove.rides.ride;

import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contrato en docs/apis/api-rides.md. El userId siempre sale del token, nunca del cuerpo. */
@RestController
@RequestMapping("/api/v1/rides")
public class RideController {

    private final RideService rides;

    public RideController(RideService rides) {
        this.rides = rides;
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('USER')")
    public StartedResponse start(@Valid @RequestBody StartRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Ride ride = rides.start(request.vehicleId(), request.reservationIdOrNull(), user);
        return new StartedResponse(ride.publicId(), ride.vehicleId(), ride.originZoneId(), ride.startTime(),
                ride.status());
    }

    @PostMapping("/{rideId}/finish")
    public FinishedResponse finish(@PathVariable String rideId, @Valid @RequestBody FinishRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Ride ride = rides.finish(rideId, request.destinationZoneId(), user);
        return new FinishedResponse(ride.publicId(), ride.endTime(), ride.durationMinutes(), ride.originZoneId(),
                ride.destinationZoneId(), ride.zoneValidated(), ride.preliminaryCost(), ride.status());
    }

    @GetMapping("/{rideId}")
    public RideView get(@PathVariable String rideId, @AuthenticationPrincipal AuthenticatedUser user) {
        Ride ride = rides.find(rideId, user);
        return new RideView(ride.publicId(), ride.vehicleId(), ride.userId(), ride.originZoneId(), ride.startTime(),
                ride.status());
    }

    /** reservationId es opcional: se omite si el usuario inicia el viaje sin reserva. */
    public record StartRequest(@NotBlank @Size(max = 64) String vehicleId, @Size(max = 64) String reservationId) {

        String reservationIdOrNull() {
            return reservationId == null || reservationId.isBlank() ? null : reservationId;
        }
    }

    public record FinishRequest(@NotBlank @Size(max = 64) String destinationZoneId) {
    }

    public record FinishedResponse(String rideId, Instant endTime, Integer durationMinutes, String originZoneId,
            String destinationZoneId, Boolean zoneValidated, BigDecimal preliminaryCost, RideStatus status) {
    }

    public record StartedResponse(String rideId, String vehicleId, String originZoneId, Instant startTime,
            RideStatus status) {
    }

    public record RideView(String rideId, String vehicleId, String userId, String originZoneId, Instant startTime,
            RideStatus status) {
    }
}
