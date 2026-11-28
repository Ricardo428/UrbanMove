package es.ulpgc.urbanmove.lambdas.relocation;

import es.ulpgc.urbanmove.events.VehicleType;
import java.time.Instant;
import java.util.List;

/** La demanda del data mart de demanda, con GET /api/v1/analytics/demand (docs/apis/api-analytics.md). */
public final class AnalyticsDemandSource implements DemandSource {

    /** "En la última hora", como en la regla del enunciado. */
    static final int WINDOW_MINUTES = 60;

    private final ServiceHttp analytics;

    public AnalyticsDemandSource(ServiceHttp analytics) {
        this.analytics = analytics;
    }

    @Override
    public Demand currentDemand(String correlationId) {
        DemandResponse response = analytics.get("/api/v1/analytics/demand?windowMinutes=" + WINDOW_MINUTES,
                DemandResponse.class, correlationId);
        List<ZoneDemand> zones = response.zones() == null ? List.of() : response.zones().stream()
                .map(row -> new ZoneDemand(row.zoneId(), row.vehicleType(), row.requests()))
                .toList();
        return new Demand(response.windowStart(), response.windowEnd(), response.dataUpTo(), zones);
    }

    /** Lo que responde analytics; reservations y ridesWithoutReservation no hacen falta, solo su suma. */
    record DemandResponse(Instant windowStart, Instant windowEnd, Instant dataUpTo, List<Row> zones) {
    }

    record Row(String zoneId, VehicleType vehicleType, int requests) {
    }
}
