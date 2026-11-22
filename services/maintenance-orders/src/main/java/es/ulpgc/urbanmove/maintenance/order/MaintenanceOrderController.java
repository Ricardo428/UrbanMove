package es.ulpgc.urbanmove.maintenance.order;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.events.MaintenanceReason;
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
 * Contrato en docs/apis/api-maintenance-orders.md. Consultar: MAINTENANCE o ADMIN. Retirar, registrar la tarea y
 * volver al servicio: solo MAINTENANCE, y el operario sale del token, nunca del cuerpo.
 */
@RestController
@RequestMapping("/api/v1/maintenance-orders")
public class MaintenanceOrderController {

    private final MaintenanceOrderService service;

    public MaintenanceOrderController(MaintenanceOrderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public List<OrderSummary> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String vehicleId) {
        return service.list(status, vehicleId).stream().map(OrderSummary::of).toList();
    }

    @GetMapping("/{orderId}")
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public OrderDetail get(@PathVariable String orderId) {
        return OrderDetail.of(service.get(orderId));
    }

    @PostMapping("/{orderId}/withdraw")
    @PreAuthorize("hasRole('MAINTENANCE')")
    public Withdrawn withdraw(@PathVariable String orderId, @AuthenticationPrincipal AuthenticatedUser operator) {
        return Withdrawn.of(service.withdraw(orderId, operator));
    }

    @PostMapping("/{orderId}/start")
    @PreAuthorize("hasRole('MAINTENANCE')")
    public Started start(@PathVariable String orderId, @RequestBody(required = false) StartRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        if (request == null) {
            throw ApiException.badRequest("Falta el cuerpo con la tarea (task)");
        }
        return Started.of(service.start(orderId, operator, MaintenanceTask.parse(request.task())));
    }

    @PostMapping("/{orderId}/complete")
    @PreAuthorize("hasRole('MAINTENANCE')")
    public Completed complete(@PathVariable String orderId, @AuthenticationPrincipal AuthenticatedUser operator) {
        return Completed.of(service.complete(orderId, operator));
    }

    public record StartRequest(String task) {
    }

    /** Fila de la lista: lo necesario para elegir qué orden abrir. */
    public record OrderSummary(String orderId, String vehicleId, MaintenanceReason reason,
            Integer batteryLevelPercent, OrderStatus status, Instant detectedAt, Instant createdAt) {

        static OrderSummary of(MaintenanceOrder order) {
            return new OrderSummary(order.publicId(), order.vehicleId(), order.reason(),
                    order.batteryLevelPercent(), order.status(), order.detectedAt(), order.createdAt());
        }
    }

    /** Orden completa, con operario, tarea, zona y la marca de tiempo de cada paso. */
    public record OrderDetail(String orderId, String vehicleId, MaintenanceReason reason,
            Integer batteryLevelPercent, OrderStatus status, MaintenanceTask task, String operatorId,
            String zoneId, Instant detectedAt, Instant createdAt, Instant withdrawnAt, Instant startedAt,
            Instant completedAt) {

        static OrderDetail of(MaintenanceOrder order) {
            return new OrderDetail(order.publicId(), order.vehicleId(), order.reason(),
                    order.batteryLevelPercent(), order.status(), order.task(), order.operatorId(), order.zoneId(),
                    order.detectedAt(), order.createdAt(), order.withdrawnAt(), order.startedAt(),
                    order.completedAt());
        }
    }

    public record Withdrawn(String orderId, String vehicleId, OrderStatus status, String operatorId,
            Instant withdrawnAt) {

        static Withdrawn of(MaintenanceOrder order) {
            return new Withdrawn(order.publicId(), order.vehicleId(), order.status(), order.operatorId(),
                    order.withdrawnAt());
        }
    }

    public record Started(String orderId, OrderStatus status, MaintenanceTask task, Instant startedAt) {

        static Started of(MaintenanceOrder order) {
            return new Started(order.publicId(), order.status(), order.task(), order.startedAt());
        }
    }

    public record Completed(String orderId, String vehicleId, OrderStatus status, String zoneId,
            Instant completedAt) {

        static Completed of(MaintenanceOrder order) {
            return new Completed(order.publicId(), order.vehicleId(), order.status(), order.zoneId(),
                    order.completedAt());
        }
    }
}
