package es.ulpgc.urbanmove.maintenance.order;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.events.payload.VehicleBatteryLow;
import es.ulpgc.urbanmove.events.payload.VehicleReturnedToService;
import es.ulpgc.urbanmove.events.payload.VehicleWithdrawnForMaintenance;
import es.ulpgc.urbanmove.maintenance.availability.AvailabilityClient;
import es.ulpgc.urbanmove.maintenance.availability.StateOutcome;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reglas de las órdenes de mantenimiento (docs/apis/api-maintenance-orders.md).
 *
 * <p>En withdraw, start y complete el orden es siempre el mismo: primero se pide a availability el estado del
 * recurso, fuera de cualquier transacción, y después se guarda la orden con su UPDATE condicional y el evento en la
 * misma transacción. El PUT /state de availability es idempotente, así que si el servicio cae entre las dos cosas,
 * el operario repite la petición y el recurso y la orden convergen. Una repetición que ya se había guardado
 * (mismo operario, misma orden en el estado de destino) responde con la orden y no emite el evento otra vez.
 */
@Service
public class MaintenanceOrderService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceOrderService.class);

    private final MaintenanceOrderRepository orders;
    private final AvailabilityClient availability;
    private final EventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public MaintenanceOrderService(MaintenanceOrderRepository orders, AvailabilityClient availability,
            EventPublisher events, TransactionTemplate transaction, Clock clock) {
        this.orders = orders;
        this.availability = availability;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Crea una orden OPEN al recibir VehicleBatteryLow. Lo llama el consumidor dentro de su transacción. No llama a
     * availability ni emite eventos. Devuelve false si no se creó (el recurso ya tiene una orden activa o la
     * detección llegó tarde).
     */
    public boolean openFromDetection(VehicleBatteryLow detection) {
        if (orders.hasCompletedAfter(detection.vehicleId(), detection.detectedAt())) {
            log.info("Detección de {} descartada: ya hay una orden completada posterior a {}",
                    detection.vehicleId(), detection.detectedAt());
            return false;
        }
        boolean created = orders.insertOpen(detection.vehicleId(), detection.reason(),
                detection.batteryLevelPercent(), detection.detectedAt());
        if (created) {
            log.info("Orden de mantenimiento abierta para {} ({})", detection.vehicleId(), detection.reason());
        } else {
            log.info("Detección de {} descartada: ya tiene una orden activa", detection.vehicleId());
        }
        return created;
    }

    public List<MaintenanceOrder> list(String status, String vehicleId) {
        return orders.list(status == null ? null : OrderStatus.parse(status), vehicleId);
    }

    public MaintenanceOrder get(String orderId) {
        return load(orderId);
    }

    /** OPEN → WITHDRAWN. Evento: VehicleWithdrawnForMaintenance. */
    public MaintenanceOrder withdraw(String orderId, AuthenticatedUser operator) {
        MaintenanceOrder order = load(orderId);
        requireNotOwnedByAnother(order, operator);
        if (order.status() == OrderStatus.WITHDRAWN) {
            return order;
        }
        requireStatus(order, OrderStatus.OPEN);

        changeVehicleState(order, "WITHDRAWN");

        Instant now = now();
        Boolean changed = transaction.execute(status -> {
            if (!orders.withdraw(order.id(), operator.userId(), now)) {
                return false;
            }
            events.publish(new VehicleWithdrawnForMaintenance(order.vehicleId(), order.publicId(), order.reason(),
                    now));
            return true;
        });
        if (!Boolean.TRUE.equals(changed)) {
            // Otro operario (o este mismo, con un doble clic) llegó antes: se mira cómo ha quedado la orden.
            MaintenanceOrder current = load(orderId);
            requireNotOwnedByAnother(current, operator);
            requireStatus(current, OrderStatus.WITHDRAWN);
        }
        return load(orderId);
    }

    /** WITHDRAWN → IN_PROGRESS con la tarea elegida. No emite evento. */
    public MaintenanceOrder start(String orderId, AuthenticatedUser operator, MaintenanceTask task) {
        MaintenanceOrder order = load(orderId);
        requireNotOwnedByAnother(order, operator);
        if (order.status() == OrderStatus.IN_PROGRESS && order.task() == task) {
            return order;
        }
        if (order.status() == OrderStatus.OPEN) {
            throw ApiException.conflict("La orden está OPEN: primero hay que retirar el recurso");
        }
        requireStatus(order, OrderStatus.WITHDRAWN);

        changeVehicleState(order, task.vehicleState());

        Boolean changed = transaction.execute(status -> orders.start(order.id(), operator.userId(), task, now()));
        if (!Boolean.TRUE.equals(changed)) {
            MaintenanceOrder current = load(orderId);
            requireNotOwnedByAnother(current, operator);
            if (current.status() != OrderStatus.IN_PROGRESS || current.task() != task) {
                throw ApiException.conflict("La orden ya no está WITHDRAWN: está " + current.status());
            }
        }
        return load(orderId);
    }

    /** IN_PROGRESS → COMPLETED. Evento: VehicleReturnedToService, con la zona en la que queda el recurso. */
    public MaintenanceOrder complete(String orderId, AuthenticatedUser operator) {
        MaintenanceOrder order = load(orderId);
        requireNotOwnedByAnother(order, operator);
        if (order.status() == OrderStatus.COMPLETED) {
            return order;
        }
        requireStatus(order, OrderStatus.IN_PROGRESS);

        // Si una petición anterior ya dejó el recurso AVAILABLE y se cayó antes de guardar la orden, availability
        // rechaza AVAILABLE → READY_FOR_SERVICE. Ese rechazo no corta: el PUT a AVAILABLE responde 200 con la zona
        // si el recurso ya está ahí y, si no, es él quien rechaza.
        StateOutcome ready = requestVehicleState(order, "READY_FOR_SERVICE");
        if (!(ready instanceof StateOutcome.Rejected)) {
            unwrapVehicleState(order, "READY_FOR_SERVICE", ready);
        }
        String zoneId = changeVehicleState(order, "AVAILABLE");
        if (zoneId == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Availability no devolvió la zona del recurso: la orden sigue IN_PROGRESS, repite la petición");
        }

        Instant now = now();
        Boolean changed = transaction.execute(status -> {
            if (!orders.complete(order.id(), operator.userId(), zoneId, now)) {
                return false;
            }
            events.publish(new VehicleReturnedToService(order.vehicleId(), order.publicId(), zoneId, now));
            return true;
        });
        if (!Boolean.TRUE.equals(changed)) {
            MaintenanceOrder current = load(orderId);
            requireNotOwnedByAnother(current, operator);
            requireStatus(current, OrderStatus.COMPLETED);
        }
        return load(orderId);
    }

    private MaintenanceOrder load(String orderId) {
        Long id = MaintenanceOrder.parseId(orderId);
        return (id == null ? Optional.<MaintenanceOrder>empty() : orders.find(id))
                .orElseThrow(() -> ApiException.notFound("La orden " + orderId + " no existe"));
    }

    /** Una orden con operario solo la sigue quien la retiró. Una OPEN no tiene operario y pasa. */
    private static void requireNotOwnedByAnother(MaintenanceOrder order, AuthenticatedUser operator) {
        if (order.operatorId() != null && !order.operatorId().equals(operator.userId())) {
            throw ApiException.forbidden("La orden " + order.publicId() + " la tiene otro operario");
        }
    }

    private static void requireStatus(MaintenanceOrder order, OrderStatus expected) {
        if (order.status() != expected) {
            throw ApiException.conflict("La orden " + order.publicId() + " no está " + expected + ": está "
                    + order.status());
        }
    }

    /** Pide el estado a availability y devuelve la zona del recurso; si no puede ser, corta con el error de la API. */
    private String changeVehicleState(MaintenanceOrder order, String state) {
        return unwrapVehicleState(order, state, requestVehicleState(order, state));
    }

    private StateOutcome requestVehicleState(MaintenanceOrder order, String state) {
        return availability.changeState(order.vehicleId(), state, "Orden de mantenimiento " + order.publicId());
    }

    private String unwrapVehicleState(MaintenanceOrder order, String state, StateOutcome outcome) {
        return switch (outcome) {
            case StateOutcome.Changed changed -> changed.zoneId();
            case StateOutcome.NotFound notFound -> throw ApiException.conflict(
                    "Availability no conoce el recurso " + order.vehicleId());
            case StateOutcome.Rejected rejected -> throw ApiException.conflict(
                    "Availability no permite pasar " + order.vehicleId() + " a " + state + ": "
                            + rejected.message());
            case StateOutcome.Unreachable unreachable -> throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Availability no responde: la orden no ha cambiado, repite la petición");
        };
    }

    private Instant now() {
        // Postgres guarda microsegundos; en milisegundos lo que se responde coincide con lo guardado.
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
