package es.ulpgc.urbanmove.zones.zone;

import es.ulpgc.urbanmove.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Contrato en docs/apis/api-zones.md. Leer es para cualquier usuario autenticado; crear y modificar, solo ADMIN. */
@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private final ZoneService zones;

    public ZoneController(ZoneService zones) {
        this.zones = zones;
    }

    @GetMapping
    public List<ZoneSummary> list(@RequestParam(required = false) String active) {
        return zones.list(parseActive(active)).stream().map(ZoneSummary::of).toList();
    }

    @GetMapping("/{zoneId}")
    public ZoneView get(@PathVariable String zoneId) {
        return ZoneView.of(zones.find(zoneId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ZoneView create(@Valid @RequestBody CreateRequest request) {
        boolean parkingAllowed = request.parkingAllowed() == null || request.parkingAllowed();
        return ZoneView.of(zones.create(new Zone(request.zoneId(), request.name().trim(), parkingAllowed, true,
                request.center().lat(), request.center().lon(), request.radiusMeters())));
    }

    @PutMapping("/{zoneId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ZoneView update(@PathVariable String zoneId, @Valid @RequestBody UpdateRequest request) {
        return ZoneView.of(zones.update(new Zone(zoneId, request.name().trim(), request.parkingAllowed(),
                request.active(), request.center().lat(), request.center().lon(), request.radiusMeters())));
    }

    /** "active" solo admite true o false; cualquier otro valor es un 400 (Spring aceptaría también yes, on, 1...). */
    private static Boolean parseActive(String active) {
        if (active == null) {
            return null;
        }
        return switch (active) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw ApiException.badRequest("El filtro active debe ser true o false");
        };
    }

    public record Center(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lon) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$",
                    message = "solo admite letras, números, guiones y guiones bajos") String zoneId,
            @NotBlank @Size(max = 128) String name,
            Boolean parkingAllowed,
            @NotNull @Valid Center center,
            @NotNull @Positive Integer radiusMeters) {
    }

    public record UpdateRequest(
            @NotBlank @Size(max = 128) String name,
            @NotNull Boolean parkingAllowed,
            @NotNull Boolean active,
            @NotNull @Valid Center center,
            @NotNull @Positive Integer radiusMeters) {
    }

    public record ZoneSummary(String zoneId, String name, boolean parkingAllowed, boolean active) {

        static ZoneSummary of(Zone zone) {
            return new ZoneSummary(zone.zoneId(), zone.name(), zone.parkingAllowed(), zone.active());
        }
    }

    public record ZoneView(String zoneId, String name, boolean parkingAllowed, boolean active, Center center,
            int radiusMeters) {

        static ZoneView of(Zone zone) {
            return new ZoneView(zone.zoneId(), zone.name(), zone.parkingAllowed(), zone.active(),
                    new Center(zone.centerLat(), zone.centerLon()), zone.radiusMeters());
        }
    }
}
