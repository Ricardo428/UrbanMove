package es.ulpgc.urbanmove.zones.zone;

import es.ulpgc.urbanmove.common.error.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ZoneService {

    private final ZoneRepository zones;

    public ZoneService(ZoneRepository zones) {
        this.zones = zones;
    }

    public List<Zone> list(Boolean active) {
        return zones.list(active);
    }

    public Zone find(String zoneId) {
        return zones.find(zoneId).orElseThrow(() -> notFound(zoneId));
    }

    public Zone create(Zone zone) {
        return zones.insert(zone).orElseThrow(
                () -> ApiException.conflict("Ya existe una zona con el zoneId " + zone.zoneId()));
    }

    public Zone update(Zone zone) {
        return zones.update(zone).orElseThrow(() -> notFound(zone.zoneId()));
    }

    private static ApiException notFound(String zoneId) {
        return ApiException.notFound("La zona " + zoneId + " no existe");
    }
}
