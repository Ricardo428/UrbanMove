package es.ulpgc.urbanmove.rides.ride;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.payload.RideFinished;
import es.ulpgc.urbanmove.events.payload.RideStarted;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import es.ulpgc.urbanmove.rides.availability.AvailabilityClient;
import es.ulpgc.urbanmove.rides.availability.StartOutcome;
import es.ulpgc.urbanmove.rides.reservations.ReservationLookup;
import es.ulpgc.urbanmove.rides.reservations.ReservationSnapshot;
import es.ulpgc.urbanmove.rides.reservations.ReservationsClient;
import es.ulpgc.urbanmove.rides.zones.ZoneLookup;
import es.ulpgc.urbanmove.rides.zones.ZonesClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ciclo de vida de un viaje. Cada cambio de estado y su evento se guardan en la misma transacción (outbox).
 * Iniciar sigue el orden del ADR-002: primero las comprobaciones que no cambian nada (la reserva), después se
 * guarda el viaje STARTING, se llama a availability fuera de la transacción y se guarda el resultado.
 */
@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    private final RideRepository rides;
    private final ReservationsClient reservations;
    private final AvailabilityClient availability;
    private final ZonesClient zones;
    private final RideTariff tariff;
    private final EventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RideService(RideRepository rides, ReservationsClient reservations, AvailabilityClient availability,
            ZonesClient zones, RideTariff tariff, EventPublisher events, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.rides = rides;
        this.reservations = reservations;
        this.availability = availability;
        this.zones = zones;
        this.tariff = tariff;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** reservationId es null si el usuario inicia el viaje sin reserva. */
    public Ride start(String vehicleId, String reservationId, AuthenticatedUser user) {
        // 1. Comprobaciones sin efectos: si fallan, no se guarda nada.
        if (reservationId != null) {
            requireUsableReservation(reservationId, vehicleId, user);
        }

        // 2. Se guarda antes de llamar a availability: si la llamada se queda a medias, queda rastro del viaje.
        //    Si es un reintento tras un 503, se reutiliza el viaje STARTING para repetir el mismo rideId.
        Ride pending = startingRideToRetry(vehicleId, reservationId, user)
                .orElseGet(() -> get(rides.insertStarting(vehicleId, user.userId(), reservationId, clock.instant())));
        long id = pending.id();
        String rideId = pending.publicId();
        Instant startTime = pending.startTime();

        // 3. Fuera de cualquier transacción, para no tener una conexión abierta mientras se espera.
        StartOutcome outcome = availability.start(vehicleId, rideId, reservationId);

        // 4. Se guarda el resultado.
        return switch (outcome) {
            case StartOutcome.Started started -> transaction.execute(status -> {
                rides.markInProgress(id, started.zoneId());
                events.publish(new RideStarted(rideId, vehicleId, user.userId(), reservationId, started.zoneId(),
                        startTime));
                return get(id);
            });
            case StartOutcome.NotAvailable notAvailable -> {
                rides.reject(id);
                throw ApiException.conflict("El vehículo no está disponible");
            }
            case StartOutcome.VehicleNotFound notFound -> {
                rides.reject(id);
                throw ApiException.notFound("El vehículo " + vehicleId + " no existe");
            }
            case StartOutcome.Unreachable unreachable -> {
                log.warn("Viaje {} queda STARTING: availability no respondió", rideId);
                throw serviceUnavailable("disponibilidad");
            }
        };
    }

    /**
     * Termina el viaje en destinationZoneId. Si zones no responde, se termina igualmente con zoneValidated false,
     * para no dejar al usuario con el viaje abierto ni el vehículo bloqueado (degradación funcional).
     */
    public Ride finish(String rideId, String destinationZoneId, AuthenticatedUser user) {
        // 1. Comprobaciones sobre el viaje.
        Ride ride = get(rideId);
        requireOwner(ride, user);
        requireInProgress(ride);

        // 2. Fuera de cualquier transacción: la zona de destino.
        boolean zoneValidated = validateDestination(destinationZoneId);

        // 3. El contador se para ahora.
        Instant endTime = clock.instant();
        int durationMinutes = tariff.durationMinutes(ride.startTime(), endTime);
        BigDecimal cost = tariff.preliminaryCost(durationMinutes);
        int battery = tariff.batteryLevelPercent(durationMinutes);

        // 4. Estado y evento en la misma transacción. Si otra finalización ganó a la vez, no cambia ninguna fila.
        return transaction.execute(status -> {
            if (!rides.complete(ride.id(), destinationZoneId, endTime, durationMinutes, zoneValidated, cost,
                    battery)) {
                throw ApiException.conflict("El viaje ya estaba finalizado");
            }
            events.publish(new RideFinished(ride.publicId(), ride.vehicleId(), ride.userId(), ride.originZoneId(),
                    destinationZoneId, endTime, durationMinutes, cost, battery));
            return get(ride.id());
        });
    }

    /** Solo su propietario o un ADMIN pueden verlo. */
    public Ride find(String rideId, AuthenticatedUser user) {
        Ride ride = get(rideId);
        if (user.role() != Role.ADMIN) {
            requireOwner(ride, user);
        }
        return ride;
    }

    private static void requireOwner(Ride ride, AuthenticatedUser user) {
        if (!ride.userId().equals(user.userId())) {
            throw ApiException.forbidden("El viaje es de otro usuario");
        }
    }

    private static void requireInProgress(Ride ride) {
        if (ride.status() == RideStatus.COMPLETED) {
            throw ApiException.conflict("El viaje ya estaba finalizado");
        }
        if (ride.status() != RideStatus.IN_PROGRESS) {
            throw ApiException.conflict("El viaje no está en curso: está " + ride.status());
        }
    }

    /** true si zones confirma la zona; false si no responde. Una zona inexistente o sin aparcamiento es un error. */
    private boolean validateDestination(String destinationZoneId) {
        return switch (zones.find(destinationZoneId)) {
            case ZoneLookup.Found found -> {
                if (!found.zone().allowsParking()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "La zona " + destinationZoneId + " no permite aparcar");
                }
                yield true;
            }
            case ZoneLookup.NotFound notFound ->
                    throw ApiException.notFound("La zona " + destinationZoneId + " no existe");
            case ZoneLookup.Unreachable unreachable -> {
                log.warn("Zones no responde: el viaje se finaliza en {} sin validar la zona", destinationZoneId);
                yield false;
            }
        };
    }

    private Optional<Ride> startingRideToRetry(String vehicleId, String reservationId, AuthenticatedUser user) {
        Optional<Ride> pending = rides.findStarting(user.userId(), vehicleId, reservationId);
        pending.ifPresent(ride -> log.info("Reintento del viaje {}: se repite la llamada a availability", ride.publicId()));
        return pending;
    }

    private void requireUsableReservation(String reservationId, String vehicleId, AuthenticatedUser user) {
        ReservationSnapshot reservation = switch (reservations.find(reservationId)) {
            case ReservationLookup.Found found -> found.reservation();
            case ReservationLookup.NotFound notFound ->
                    throw ApiException.notFound("La reserva " + reservationId + " no existe");
            case ReservationLookup.Unreachable unreachable -> throw serviceUnavailable("reservas");
        };
        if (!reservation.belongsTo(user.userId())) {
            throw ApiException.forbidden("La reserva es de otro usuario");
        }
        if (!reservation.isFor(vehicleId)) {
            throw ApiException.conflict("La reserva es de otro vehículo");
        }
        if (!reservation.isActive()) {
            throw ApiException.conflict("La reserva ya no está activa: está " + reservation.status());
        }
    }

    private Ride get(String rideId) {
        long id = Ride.parsePublicId(rideId).orElseThrow(() -> notFound(rideId));
        return rides.find(id).orElseThrow(() -> notFound(rideId));
    }

    private Ride get(long id) {
        return rides.find(id).orElseThrow(() -> notFound(Ride.publicIdOf(id)));
    }

    private static ApiException notFound(String rideId) {
        return ApiException.notFound("El viaje " + rideId + " no existe");
    }

    private static ApiException serviceUnavailable(String serviceName) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "El servicio de " + serviceName + " no responde. Inténtalo de nuevo en unos segundos");
    }
}
