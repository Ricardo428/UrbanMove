package es.ulpgc.urbanmove.relocation.order;

import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrato en docs/apis/api-relocation-orders.md. No hay endpoint para crear órdenes: nacen de DemandHotspotDetected.
 * El operario siempre sale del token, nunca del cuerpo.
 */
@RestController
@RequestMapping("/api/v1/relocation-orders")
public class RelocationOrderController {

    private final RelocationOrderService service;

    public RelocationOrderController(RelocationOrderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public List<OrderSummary> list(@RequestParam(required = false) String status) {
        return service.list(status).stream().map(OrderSummary::of).toList();
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public OrderDetail get(@PathVariable String orderId) {
        return OrderDetail.of(service.get(orderId));
    }

    /** Solo un operario: el que recoge queda como dueño de la orden y es el único que podrá completarla. */
    @PostMapping("/{orderId}/pickup")
    @PreAuthorize("hasRole('MAINTENANCE')")
    public PickedUp pickup(@PathVariable String orderId, @RequestBody PickupRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        RelocationOrderService.PickupResult result = service.pickup(orderId, operator.userId(), request.vehicleIds());
        return new PickedUp(result.order().publicId(), result.order().status(), result.withdrawn(),
                result.rejected());
    }

    /** Solo el operario que la recogió: se compara el operator_id guardado con el usuario del token. */
    @PostMapping("/{orderId}/complete")
    @PreAuthorize("hasRole('MAINTENANCE')")
    public Completed complete(@PathVariable String orderId, @AuthenticationPrincipal AuthenticatedUser operator) {
        RelocationOrderService.OrderWithVehicles result = service.complete(orderId, operator.userId());
        RelocationOrder order = result.order();
        return new Completed(order.publicId(), order.status(), result.vehicleIds(), order.toZoneId(),
                order.completedAt());
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public Cancelled cancel(@PathVariable String orderId) {
        RelocationOrder order = service.cancel(orderId);
        return new Cancelled(order.publicId(), order.status());
    }

    /** Fila de la lista: lo necesario para elegir qué orden recoger. */
    public record OrderSummary(String orderId, String fromZoneId, String toZoneId, VehicleType vehicleType,
            int quantity, OrderStatus status, Instant createdAt) {

        static OrderSummary of(RelocationOrder order) {
            return new OrderSummary(order.publicId(), order.fromZoneId(), order.toZoneId(), order.vehicleType(),
                    order.quantity(), order.status(), order.createdAt());
        }
    }

    /** Orden completa, con su operario, los recursos reclamados y la marca de tiempo de cada paso. */
    public record OrderDetail(String orderId, String fromZoneId, String toZoneId, VehicleType vehicleType,
            int quantity, OrderStatus status, String operatorId, List<String> vehicleIds, Instant createdAt,
            Instant pickedUpAt, Instant completedAt) {

        static OrderDetail of(RelocationOrderService.OrderWithVehicles detail) {
            RelocationOrder order = detail.order();
            return new OrderDetail(order.publicId(), order.fromZoneId(), order.toZoneId(), order.vehicleType(),
                    order.quantity(), order.status(), order.operatorId(), detail.vehicleIds(), order.createdAt(),
                    order.pickedUpAt(), order.completedAt());
        }
    }

    public record Cancelled(String orderId, OrderStatus status) {
    }

    public record Completed(String orderId, OrderStatus status, List<String> vehicleIds, String toZoneId,
            Instant completedAt) {
    }

    /** Las validaciones (vacío, más que quantity) dependen de la orden: las hace el servicio. */
    public record PickupRequest(List<String> vehicleIds) {
    }

    public record PickedUp(String orderId, OrderStatus status, List<String> withdrawn,
            List<RelocationOrderService.Rejection> rejected) {
    }
}
