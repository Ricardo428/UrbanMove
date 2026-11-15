package es.ulpgc.urbanmove.history.entry;

import com.fasterxml.jackson.databind.JsonNode;
import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrato en docs/apis/api-history.md. Solo consultas: el historial se escribe con los eventos.
 * En /me el userId sale del token, nunca de la URL: nadie puede pedir el historial de otro.
 */
@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    static final int MAX_LIMIT = 200;

    private final HistoryRepository history;

    public HistoryController(HistoryRepository history) {
        this.history = history;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    public MyHistory me(@RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthenticatedUser user) {
        return new MyHistory(history.findByUser(user.userId(), checked(limit)).stream()
                .map(MyEntry::of)
                .toList());
    }

    /** History no pregunta a Inventory si el vehículo existe: uno desconocido devuelve una lista vacía. */
    @GetMapping("/vehicles/{vehicleId}")
    @PreAuthorize("hasAnyRole('MAINTENANCE', 'ADMIN')")
    public VehicleHistory vehicle(@PathVariable String vehicleId, @RequestParam(defaultValue = "50") int limit) {
        return new VehicleHistory(history.findByVehicle(vehicleId, checked(limit)).stream()
                .map(VehicleEntry::of)
                .toList());
    }

    private static int checked(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit debe estar entre 1 y " + MAX_LIMIT);
        }
        return limit;
    }

    public record MyHistory(List<MyEntry> entries) {
    }

    /** Entrada de /me: sin userId, porque siempre es el del token. */
    public record MyEntry(UUID eventId, String type, Instant occurredAt, String vehicleId, String reservationId,
            String rideId, JsonNode details) {

        static MyEntry of(HistoryEntry entry) {
            return new MyEntry(entry.eventId(), entry.type(), entry.occurredAt(), entry.vehicleId(),
                    entry.reservationId(), entry.rideId(), entry.details());
        }
    }

    public record VehicleHistory(List<VehicleEntry> entries) {
    }

    /** Entrada de /vehicles/{id}: con userId, para saber quién lo usó. */
    public record VehicleEntry(UUID eventId, String type, Instant occurredAt, String userId, String vehicleId,
            String reservationId, String rideId, JsonNode details) {

        static VehicleEntry of(HistoryEntry entry) {
            return new VehicleEntry(entry.eventId(), entry.type(), entry.occurredAt(), entry.userId(),
                    entry.vehicleId(), entry.reservationId(), entry.rideId(), entry.details());
        }
    }
}
