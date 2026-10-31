package es.ulpgc.urbanmove.reservations.reservation;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.payload.ReservationCancelled;
import es.ulpgc.urbanmove.events.payload.ReservationExpired;
import es.ulpgc.urbanmove.events.payload.VehicleReserved;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import es.ulpgc.urbanmove.reservations.ReservationsProperties;
import es.ulpgc.urbanmove.reservations.availability.AvailabilityClient;
import es.ulpgc.urbanmove.reservations.availability.ReserveOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ciclo de vida de una reserva. Cada cambio de estado y su evento se guardan en la misma transacción (outbox).
 * Crear una reserva sigue el orden del ADR-002: guardar PENDING, llamar a availability fuera de la transacción
 * y guardar el resultado. Así, si availability no responde, la reserva queda PENDING y caduca sola.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservations;
    private final AvailabilityClient availability;
    private final EventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration pendingTtl;
    private final Duration confirmedTtl;

    public ReservationService(ReservationRepository reservations, AvailabilityClient availability,
            EventPublisher events, PlatformTransactionManager transactionManager, Clock clock,
            ReservationsProperties properties) {
        this.reservations = reservations;
        this.availability = availability;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.pendingTtl = properties.pendingTtl();
        this.confirmedTtl = properties.confirmedTtl();
    }

    public Reservation create(String vehicleId, AuthenticatedUser user) {
        Instant now = clock.instant();
        // 1. Se guarda antes de llamar a availability: si la llamada se queda a medias, la reserva existe y caduca.
        long id = reservations.insertPending(vehicleId, user.userId(), now, now.plus(pendingTtl));
        String reservationId = Reservation.publicIdOf(id);

        // 2. Fuera de cualquier transacción, para no tener una conexión abierta mientras se espera.
        ReserveOutcome outcome = availability.reserve(vehicleId, reservationId);

        // 3. Se guarda el resultado.
        return switch (outcome) {
            case ReserveOutcome.Reserved reserved -> transaction.execute(status -> {
                reservations.setZone(id, reserved.zoneId());
                Reservation reservation = get(id);
                events.publish(new VehicleReserved(reservationId, vehicleId, user.userId(), reserved.zoneId(),
                        reservation.expiresAt()));
                return reservation;
            });
            case ReserveOutcome.NotAvailable notAvailable -> {
                reservations.reject(id);
                throw ApiException.conflict("El vehículo no está disponible");
            }
            case ReserveOutcome.VehicleNotFound notFound -> {
                reservations.reject(id);
                throw ApiException.notFound("El vehículo " + vehicleId + " no existe");
            }
            case ReserveOutcome.Unreachable unreachable -> {
                log.warn("Reserva {} queda PENDING: availability no respondió", reservationId);
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "El servicio de disponibilidad no responde. Inténtalo de nuevo en unos segundos");
            }
        };
    }

    /** Solo su propietario o un ADMIN pueden verla. */
    public Reservation find(String reservationId, AuthenticatedUser user) {
        Reservation reservation = get(reservationId);
        if (user.role() != Role.ADMIN) {
            requireOwner(reservation, user);
        }
        return reservation;
    }

    public Reservation confirm(String reservationId, AuthenticatedUser user) {
        Reservation reservation = get(reservationId);
        requireOwner(reservation, user);
        Instant now = clock.instant();
        if (!reservations.confirm(reservation.id(), now, now.plus(confirmedTtl))) {
            throw ApiException.conflict("La reserva no está pendiente: caducó, se canceló o ya estaba confirmada");
        }
        return get(reservation.id());
    }

    public Reservation cancel(String reservationId, AuthenticatedUser user) {
        Reservation reservation = get(reservationId);
        requireOwner(reservation, user);
        return transaction.execute(status -> {
            if (!reservations.cancel(reservation.id())) {
                throw ApiException.conflict("La reserva ya no se puede cancelar: está " + current(reservation));
            }
            events.publish(new ReservationCancelled(reservation.publicId(), reservation.vehicleId(),
                    reservation.userId(), clock.instant()));
            return get(reservation.id());
        });
    }

    /** Lo invoca la Lambda de expiración. Repetirlo sobre una reserva ya EXPIRED no vuelve a publicar el evento. */
    public ExpiredReservation expire(String reservationId) {
        Reservation reservation = get(reservationId);
        Instant now = clock.instant();
        return transaction.execute(status -> {
            if (reservations.expire(reservation.id(), now)) {
                events.publish(new ReservationExpired(reservation.publicId(), reservation.vehicleId(),
                        reservation.userId(), now));
                return new ExpiredReservation(get(reservation.id()), now);
            }
            Reservation latest = get(reservation.id());
            if (latest.status() == ReservationStatus.EXPIRED) {
                return new ExpiredReservation(latest, latest.expiresAt());
            }
            if (latest.status().isActive()) {
                throw ApiException.conflict("La reserva todavía no ha vencido");
            }
            throw ApiException.conflict("La reserva no se puede expirar: está " + latest.status());
        });
    }

    /** Efecto de RideStarted: la reserva se ha usado para iniciar un viaje y ya no debe caducar. */
    public void markUsed(String reservationId) {
        Reservation.parsePublicId(reservationId).ifPresent(id -> {
            if (!reservations.markUsed(id)) {
                log.info("RideStarted con la reserva {} ignorado: no existe o ya no está activa", reservationId);
            }
        });
    }

    public record ExpiredReservation(Reservation reservation, Instant expiredAt) {
    }

    private Reservation get(String reservationId) {
        long id = Reservation.parsePublicId(reservationId).orElseThrow(() -> notFound(reservationId));
        return reservations.find(id).orElseThrow(() -> notFound(reservationId));
    }

    private Reservation get(long id) {
        return reservations.find(id).orElseThrow(() -> notFound(Reservation.publicIdOf(id)));
    }

    private static ApiException notFound(String reservationId) {
        return ApiException.notFound("La reserva " + reservationId + " no existe");
    }

    private ReservationStatus current(Reservation reservation) {
        return get(reservation.id()).status();
    }

    private static void requireOwner(Reservation reservation, AuthenticatedUser user) {
        if (!reservation.userId().equals(user.userId())) {
            throw ApiException.forbidden("La reserva es de otro usuario");
        }
    }
}
