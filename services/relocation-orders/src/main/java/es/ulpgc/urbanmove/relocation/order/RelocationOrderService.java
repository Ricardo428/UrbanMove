package es.ulpgc.urbanmove.relocation.order;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.events.payload.DemandHotspotDetected;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCompleted;
import es.ulpgc.urbanmove.events.payload.RelocationOrderCreated;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import es.ulpgc.urbanmove.relocation.RelocationProperties;
import es.ulpgc.urbanmove.relocation.availability.AvailabilityClient;
import es.ulpgc.urbanmove.relocation.availability.AvailabilityUnreachableException;
import es.ulpgc.urbanmove.relocation.availability.CountOutcome;
import es.ulpgc.urbanmove.relocation.availability.StateOutcome;
import es.ulpgc.urbanmove.relocation.availability.VehicleOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Reglas de las órdenes de recolocación (docs/apis/api-relocation-orders.md). */
@Service
public class RelocationOrderService {

    private static final Logger log = LoggerFactory.getLogger(RelocationOrderService.class);

    private final RelocationOrderRepository orders;
    private final AvailabilityClient availability;
    private final EventPublisher events;
    private final RelocationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    /**
     * TransactionTemplate y no @Transactional en el paso final de complete: @Transactional funciona con un proxy
     * alrededor del bean, y una llamada desde dentro de la propia clase no pasa por él.
     */
    public RelocationOrderService(RelocationOrderRepository orders, AvailabilityClient availability,
            EventPublisher events, RelocationProperties properties, Clock clock, TransactionTemplate transaction) {
        this.orders = orders;
        this.availability = availability;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.transaction = transaction;
    }

    /**
     * Crea una orden OPEN al recibir DemandHotspotDetected y publica RelocationOrderCreated en la outbox.
     *
     * <p>Lo llama el consumidor dentro de su transacción (la de processed_events), y @Transactional se une a ella:
     * la orden, el evento y la marca de procesado se guardan o se deshacen juntos. La llamada a availability ocurre
     * dentro de esa transacción, a diferencia de las peticiones de los usuarios: aquí no hay nadie esperando y, si
     * availability no responde, hay que deshacerlo todo para que SQS reentregue el mensaje. El coste aceptado es
     * tener una conexión abierta hasta el timeout (3 s).
     *
     * @return el orderId creado, o vacío si no hacía falta orden o ya había una activa para esa zona y tipo
     * @throws AvailabilityUnreachableException si availability no responde (SQS reintentará)
     */
    @Transactional
    public Optional<String> createFromHotspot(DemandHotspotDetected hotspot) {
        CountOutcome.Counted counts = switch (availability.countAvailableByZone(hotspot.vehicleType())) {
            case CountOutcome.Counted counted -> counted;
            case CountOutcome.Unreachable unreachable -> throw new AvailabilityUnreachableException(
                    "Availability no responde: no se puede decidir la orden para " + hotspot.zoneId());
        };

        Optional<RelocationPlan> plan = RelocationPlan.decide(hotspot.zoneId(), counts,
                properties.targetAvailable(), properties.minLeftInOrigin());
        if (plan.isEmpty()) {
            log.info("Hotspot de {} {} sin orden: no hace falta o ninguna zona puede ceder (recuento {})",
                    hotspot.zoneId(), hotspot.vehicleType(), counts.availableByZone());
            return Optional.empty();
        }

        Instant now = now();
        RelocationPlan chosen = plan.get();
        Optional<Long> id = orders.insertOpen(chosen.fromZoneId(), hotspot.zoneId(), hotspot.vehicleType(),
                chosen.quantity(), now);
        if (id.isEmpty()) {
            log.info("Hotspot de {} {} sin orden: ya hay una orden activa para esa zona y tipo", hotspot.zoneId(),
                    hotspot.vehicleType());
            return Optional.empty();
        }

        String orderId = RelocationOrder.publicId(id.get());
        events.publish(new RelocationOrderCreated(orderId, chosen.fromZoneId(), hotspot.zoneId(),
                hotspot.vehicleType(), chosen.quantity(), now));
        log.info("Orden {} creada: {} {} de {} a {}", orderId, chosen.quantity(), hotspot.vehicleType(),
                chosen.fromZoneId(), hotspot.zoneId());
        return Optional.of(orderId);
    }

    /** status es opcional; un valor que no sea uno de los cuatro estados es un 400. */
    public List<RelocationOrder> list(String status) {
        return orders.list(status == null ? null : OrderStatus.parse(status));
    }

    public OrderWithVehicles get(String orderId) {
        RelocationOrder order = load(orderId);
        return new OrderWithVehicles(order, orders.vehicleIds(order.id()));
    }

    /**
     * OPEN → CANCELLED con un UPDATE condicional. No toca ningún recurso: una orden OPEN no ha retirado ninguno.
     * Al dejar de estar activa, sale del índice de órdenes activas y un nuevo hotspot de esa zona puede crear otra.
     */
    public RelocationOrder cancel(String orderId) {
        RelocationOrder order = load(orderId);
        if (!orders.cancel(order.id())) {
            throw ApiException.conflict("La orden ya no está OPEN: está " + reload(order).status());
        }
        log.info("Orden {} cancelada", order.publicId());
        return reload(order);
    }

    /**
     * El operario recoge recursos en la zona origen (contrato, sección 3). Sin @Transactional a propósito: cada
     * sentencia se confirma sola y las llamadas a availability van fuera de cualquier transacción, porque hay un
     * operario esperando y no se mantiene una conexión abierta mientras se habla con otro servicio. Si algo falla a
     * mitad, no se deshace nada: lo hecho queda apuntado y el operario repite la petición (reintento idempotente).
     *
     * <ol>
     *   <li>Reclama la orden: OPEN → IN_PROGRESS con un UPDATE condicional, antes de tocar ningún recurso. Si ya es
     *   IN_PROGRESS del mismo operario, es un reintento.</li>
     *   <li>Por cada recurso: GET (tipo, zona, estado), reclamarlo en relocation_order_vehicles y PUT WITHDRAWN.</li>
     *   <li>Resultado parcial: los que fallan van en rejected. Si no se retiró ninguno y todos se rechazaron con
     *   seguridad, la orden vuelve a OPEN (409); si alguno quedó sin respuesta, sigue IN_PROGRESS (503).</li>
     * </ol>
     */
    public PickupResult pickup(String orderId, String operatorId, List<String> vehicleIds) {
        RelocationOrder order = load(orderId);
        Set<String> requested = requestedVehicles(order, vehicleIds);
        claimOrder(order, operatorId);

        String reason = "Orden de recolocación " + order.publicId();
        Set<String> claimed = new LinkedHashSet<>(orders.vehicleIds(order.id()));
        List<String> withdrawn = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        boolean unanswered = false;
        for (String vehicleId : requested) {
            boolean alreadyClaimed = claimed.contains(vehicleId);
            if (!alreadyClaimed && claimed.size() >= order.quantity()) {
                rejected.add(new Rejection(vehicleId, "La orden ya tiene sus " + order.quantity() + " recursos"));
                continue;
            }
            switch (withdrawOne(order, vehicleId, alreadyClaimed, reason)) {
                case Withdrawn w -> {
                    withdrawn.add(vehicleId);
                    claimed.add(vehicleId);
                }
                case Refused refused -> {
                    rejected.add(new Rejection(vehicleId, refused.reason()));
                    claimed.remove(vehicleId);
                }
                case Unanswered u -> {
                    unanswered = true;
                    rejected.add(new Rejection(vehicleId, "Availability no respondió: repite la petición"));
                    if (u.claimKept()) {
                        claimed.add(vehicleId);
                    }
                }
            }
        }

        if (withdrawn.isEmpty()) {
            if (unanswered) {
                throw unavailable();
            }
            orders.reopen(order.id(), operatorId);
            throw ApiException.conflict("No se pudo retirar ningún recurso: " + describe(rejected));
        }
        log.info("Orden {}: {} retirados por {}, {} rechazados", order.publicId(), withdrawn, operatorId,
                rejected.size());
        return new PickupResult(reload(order), withdrawn, rejected);
    }

    /**
     * El operario deja los recursos en la zona destino (contrato, sección 4). Como /pickup, las llamadas a
     * availability van fuera de cualquier transacción; solo el paso final es una: COMPLETED, los recursos dejan de
     * estar activos y RelocationOrderCompleted en la outbox, juntos o nada.
     *
     * <p>Cada recurso se consulta antes del PUT (decisión D): en un reintento puede estar ya READY_FOR_SERVICE o
     * AVAILABLE en el destino, y pedirle READY_FOR_SERVICE otra vez daría un 409, porque no es el mismo estado.
     * Si algo falla a mitad, la orden sigue IN_PROGRESS y el operario repite la petición.
     */
    public OrderWithVehicles complete(String orderId, String operatorId) {
        RelocationOrder order = load(orderId);
        List<String> vehicleIds = orders.vehicleIds(order.id());
        if (order.status() == OrderStatus.COMPLETED && operatorId.equals(order.operatorId())) {
            // Reintento (decisión C): la respuesta se perdió. Sin llamar a availability ni otro evento.
            return new OrderWithVehicles(order, vehicleIds);
        }
        if (order.operatorId() != null && !operatorId.equals(order.operatorId())) {
            throw ApiException.forbidden("La orden la recogió otro operario");
        }
        if (order.status() != OrderStatus.IN_PROGRESS) {
            throw ApiException.conflict("La orden no está IN_PROGRESS: está " + order.status());
        }
        if (vehicleIds.isEmpty()) {
            throw ApiException.conflict("La orden no tiene recursos retirados: repite el pickup");
        }

        String reason = "Orden de recolocación " + order.publicId();
        for (String vehicleId : vehicleIds) {
            deliver(order, vehicleId, reason);
        }

        Instant now = now();
        boolean completed = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!orders.complete(order.id(), operatorId, now)) {
                return false;
            }
            orders.deactivateVehicles(order.id());
            events.publish(new RelocationOrderCompleted(order.publicId(), vehicleIds, order.toZoneId(), now));
            return true;
        }));
        RelocationOrder current = reload(order);
        if (!completed && current.status() != OrderStatus.COMPLETED) {
            throw ApiException.conflict("La orden no está IN_PROGRESS: está " + current.status());
        }
        // !completed y COMPLETED: otra petición del mismo operario la completó a la vez; esa emitió el evento.
        if (completed) {
            log.info("Orden {} completada por {}: {} en {}", order.publicId(), operatorId, vehicleIds,
                    order.toZoneId());
        }
        return new OrderWithVehicles(current, vehicleIds);
    }

    /** Una orden y los recursos que ha reclamado. */
    public record OrderWithVehicles(RelocationOrder order, List<String> vehicleIds) {
    }

    /** Lo que responde /pickup: la orden ya IN_PROGRESS, los recursos retirados y los que no, con el motivo. */
    public record PickupResult(RelocationOrder order, List<String> withdrawn, List<Rejection> rejected) {
    }

    public record Rejection(String vehicleId, String reason) {
    }

    /** Sin duplicados, en el orden pedido. Vacío o más que quantity es un 400 (contrato). */
    private static Set<String> requestedVehicles(RelocationOrder order, List<String> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()
                || vehicleIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw ApiException.badRequest("vehicleIds no puede estar vacío");
        }
        Set<String> requested = new LinkedHashSet<>(vehicleIds);
        if (requested.size() > order.quantity()) {
            throw ApiException.badRequest("La orden es de " + order.quantity() + " recursos y se pidieron "
                    + requested.size());
        }
        return requested;
    }

    /**
     * OPEN → IN_PROGRESS. Si el UPDATE cambia 0 filas, se relee para saber por qué: el mismo operario repitiendo
     * (sigue), otro operario (403) o la orden ya no está activa (409). El perdedor no ha tocado ningún recurso.
     */
    private void claimOrder(RelocationOrder order, String operatorId) {
        if (orders.claim(order.id(), operatorId, now())) {
            return;
        }
        RelocationOrder current = reload(order);
        if (current.status() == OrderStatus.IN_PROGRESS && operatorId.equals(current.operatorId())) {
            return;
        }
        if (current.status() == OrderStatus.IN_PROGRESS) {
            throw ApiException.forbidden("La orden la recogió otro operario");
        }
        throw ApiException.conflict("La orden ya no está OPEN: está " + current.status());
    }

    /** Los tres pasos de un recurso: comprobarlo, reclamarlo en esta tabla y pedir el WITHDRAWN. */
    private VehicleStep withdrawOne(RelocationOrder order, String vehicleId, boolean alreadyClaimed, String reason) {
        switch (availability.get(vehicleId)) {
            case VehicleOutcome.NotFound notFound -> {
                return refuse(order, vehicleId, alreadyClaimed, "El vehículo no existe");
            }
            case VehicleOutcome.Unreachable unreachable -> {
                return new Unanswered(alreadyClaimed);
            }
            case VehicleOutcome.Found found -> {
                String problem = problemWith(order, found, alreadyClaimed);
                if (problem != null) {
                    return refuse(order, vehicleId, alreadyClaimed, problem);
                }
            }
        }

        if (!alreadyClaimed && !orders.claimVehicle(order.id(), vehicleId, now())) {
            return new Refused("El vehículo está en otra orden de recolocación activa");
        }

        return switch (availability.changeState(vehicleId, "WITHDRAWN", null, reason)) {
            case StateOutcome.Changed changed -> new Withdrawn();
            case StateOutcome.NotFound notFound -> refuse(order, vehicleId, true, "El vehículo no existe");
            case StateOutcome.Rejected rejected -> refuse(order, vehicleId, true, rejected.message());
            // No se sabe si el PUT se aplicó: la fila se queda y el mismo PUT (idempotente) lo confirmará.
            case StateOutcome.Unreachable unreachable -> new Unanswered(true);
        };
    }

    /**
     * Tipo y zona origen de la orden, y AVAILABLE; o WITHDRAWN si ya lo había reclamado esta orden (un reintento).
     * Un WITHDRAWN sin reclamar es de otro (por ejemplo, Órdenes Mantenimiento) aunque el PUT respondería 200.
     *
     * @return el motivo del rechazo, o null si se puede retirar
     */
    private static String problemWith(RelocationOrder order, VehicleOutcome.Found vehicle, boolean alreadyClaimed) {
        if (!order.vehicleType().name().equals(vehicle.vehicleType())) {
            return "El vehículo es " + vehicle.vehicleType() + " y la orden es de " + order.vehicleType();
        }
        if (!order.fromZoneId().equals(vehicle.zoneId())) {
            return "El vehículo está en " + vehicle.zoneId() + ", no en la zona origen " + order.fromZoneId();
        }
        boolean ok = "AVAILABLE".equals(vehicle.status()) || (alreadyClaimed && "WITHDRAWN".equals(vehicle.status()));
        return ok ? null : "El vehículo no está disponible: " + vehicle.status();
    }

    /** Rechazo seguro: si la orden tenía el recurso reclamado, lo suelta para que pueda entrar en otra orden. */
    private Refused refuse(RelocationOrder order, String vehicleId, boolean claimHeld, String reason) {
        if (claimHeld) {
            orders.releaseVehicle(order.id(), vehicleId);
        }
        return new Refused(reason);
    }

    /**
     * Deja un recurso AVAILABLE en la zona destino según cómo esté ahora (decisión D):
     * WITHDRAWN → READY_FOR_SERVICE con zoneId y AVAILABLE; READY_FOR_SERVICE en el destino → AVAILABLE;
     * AVAILABLE, RESERVED o IN_USE en el destino → ya entregado (un intento anterior llegó hasta el final).
     */
    private void deliver(RelocationOrder order, String vehicleId, String reason) {
        VehicleOutcome.Found vehicle = switch (availability.get(vehicleId)) {
            case VehicleOutcome.Found found -> found;
            case VehicleOutcome.NotFound notFound -> throw ApiException.conflict(
                    "El vehículo " + vehicleId + " no existe");
            case VehicleOutcome.Unreachable unreachable -> throw unavailable();
        };
        boolean inDestination = order.toZoneId().equals(vehicle.zoneId());
        String status = vehicle.status();
        if ("WITHDRAWN".equals(status)) {
            changeOrFail(vehicleId, "READY_FOR_SERVICE", order.toZoneId(), reason);
            changeOrFail(vehicleId, "AVAILABLE", null, reason);
        } else if ("READY_FOR_SERVICE".equals(status) && inDestination) {
            changeOrFail(vehicleId, "AVAILABLE", null, reason);
        } else if (!(inDestination && List.of("AVAILABLE", "RESERVED", "IN_USE").contains(status))) {
            throw ApiException.conflict("El vehículo " + vehicleId + " está " + status + " en " + vehicle.zoneId()
                    + ": no se puede completar la orden");
        }
    }

    private void changeOrFail(String vehicleId, String state, String zoneId, String reason) {
        switch (availability.changeState(vehicleId, state, zoneId, reason)) {
            case StateOutcome.Changed changed -> {
            }
            case StateOutcome.NotFound notFound -> throw ApiException.conflict(
                    "El vehículo " + vehicleId + " no existe");
            case StateOutcome.Rejected rejected -> throw ApiException.conflict(
                    "Availability no deja pasar " + vehicleId + " a " + state + ": " + rejected.message());
            case StateOutcome.Unreachable unreachable -> throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Availability no responde. La orden sigue IN_PROGRESS: repite la petición");
    }

    private static String describe(List<Rejection> rejected) {
        return rejected.stream().map(r -> r.vehicleId() + " (" + r.reason() + ")").collect(Collectors.joining(", "));
    }

    /** Al microsegundo, que es lo que guarda PostgreSQL: los eventos llevan la misma hora que la fila. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Cómo acabó un recurso en /pickup. */
    private sealed interface VehicleStep permits Withdrawn, Refused, Unanswered {
    }

    /** Está WITHDRAWN y reclamado por esta orden. */
    private record Withdrawn() implements VehicleStep {
    }

    /** Rechazado con seguridad: no está reclamado por esta orden. */
    private record Refused(String reason) implements VehicleStep {
    }

    /** Availability no respondió. claimKept: la fila sigue en relocation_order_vehicles porque el PUT quizá se aplicó. */
    private record Unanswered(boolean claimKept) implements VehicleStep {
    }

    private RelocationOrder load(String orderId) {
        Long id = RelocationOrder.parseId(orderId);
        return (id == null ? Optional.<RelocationOrder>empty() : orders.find(id))
                .orElseThrow(() -> ApiException.notFound("La orden " + orderId + " no existe"));
    }

    private RelocationOrder reload(RelocationOrder order) {
        return orders.find(order.id()).orElseThrow();
    }
}
