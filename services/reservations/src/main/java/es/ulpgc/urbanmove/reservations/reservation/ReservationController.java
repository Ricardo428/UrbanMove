package es.ulpgc.urbanmove.reservations.reservation;

import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.reservations.reservation.ReservationService.ExpiredReservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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

/** Contrato en docs/apis/api-reservations.md. El userId siempre sale del token, nunca del cuerpo. */
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('USER')")
    public CreatedResponse create(@Valid @RequestBody CreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Reservation reservation = reservations.create(request.vehicleId(), user);
        return new CreatedResponse(reservation.publicId(), reservation.vehicleId(), reservation.status(),
                reservation.expiresAt());
    }

    @GetMapping("/{reservationId}")
    public ReservationView get(@PathVariable String reservationId, @AuthenticationPrincipal AuthenticatedUser user) {
        Reservation reservation = reservations.find(reservationId, user);
        return new ReservationView(reservation.publicId(), reservation.vehicleId(), reservation.userId(),
                reservation.status(), reservation.createdAt(), reservation.expiresAt());
    }

    @PostMapping("/{reservationId}/confirm")
    public ConfirmedResponse confirm(@PathVariable String reservationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Reservation reservation = reservations.confirm(reservationId, user);
        return new ConfirmedResponse(reservation.publicId(), reservation.status(), reservation.expiresAt());
    }

    @PostMapping("/{reservationId}/cancel")
    public CancelledResponse cancel(@PathVariable String reservationId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Reservation reservation = reservations.cancel(reservationId, user);
        return new CancelledResponse(reservation.publicId(), reservation.status(),
                "Reserva cancelada. El vehículo vuelve a estar disponible.");
    }

    /** Interno: las reservas vencidas que la Lambda de expiración debe cerrar. */
    @GetMapping("/overdue")
    @PreAuthorize("hasRole('ADMIN')")
    public OverdueResponse overdue() {
        return new OverdueResponse(reservations.overdueIds());
    }

    /** Interno: lo llama la Lambda de expiración con su cuenta de servicio ADMIN. */
    @PostMapping("/{reservationId}/expire")
    @PreAuthorize("hasRole('ADMIN')")
    public ExpiredResponse expire(@PathVariable String reservationId) {
        ExpiredReservation expired = reservations.expire(reservationId);
        return new ExpiredResponse(expired.reservation().publicId(), expired.reservation().status(),
                expired.expiredAt());
    }

    public record CreateRequest(@NotBlank @Size(max = 64) String vehicleId) {
    }

    public record CreatedResponse(String reservationId, String vehicleId, ReservationStatus status,
            Instant expiresAt) {
    }

    public record ReservationView(String reservationId, String vehicleId, String userId, ReservationStatus status,
            Instant createdAt, Instant expiresAt) {
    }

    public record ConfirmedResponse(String reservationId, ReservationStatus status, Instant expiresAt) {
    }

    public record CancelledResponse(String reservationId, ReservationStatus status, String message) {
    }

    public record OverdueResponse(List<String> reservationIds) {
    }

    public record ExpiredResponse(String reservationId, ReservationStatus status, Instant expiredAt) {
    }
}
