package es.ulpgc.urbanmove.zones.zone;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ZoneRepository {

    private static final RowMapper<Zone> ROW = (rs, rowNum) -> new Zone(
            rs.getString("zone_id"),
            rs.getString("name"),
            rs.getBoolean("parking_allowed"),
            rs.getBoolean("active"),
            rs.getDouble("center_lat"),
            rs.getDouble("center_lon"),
            rs.getInt("radius_meters"));

    private final NamedParameterJdbcTemplate jdbc;

    public ZoneRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Zone> find(String zoneId) {
        return jdbc.query("SELECT * FROM zones WHERE zone_id = :id", new MapSqlParameterSource("id", zoneId), ROW)
                .stream().findFirst();
    }

    /** active es opcional: null significa "todas". */
    public List<Zone> list(Boolean active) {
        if (active == null) {
            return jdbc.query("SELECT * FROM zones ORDER BY zone_id", ROW);
        }
        return jdbc.query("SELECT * FROM zones WHERE active = :active ORDER BY zone_id",
                new MapSqlParameterSource("active", active), ROW);
    }

    /** Crea la zona activa. Si el zoneId ya existe no inserta nada y devuelve vacío. */
    public Optional<Zone> insert(Zone zone) {
        return jdbc.query("""
                        INSERT INTO zones (zone_id, name, parking_allowed, center_lat, center_lon, radius_meters)
                        VALUES (:id, :name, :parking, :lat, :lon, :radius)
                        ON CONFLICT (zone_id) DO NOTHING
                        RETURNING *
                        """,
                params(zone), ROW).stream().findFirst();
    }

    /** Sustituye los datos de la zona. Si no existe devuelve vacío. */
    public Optional<Zone> update(Zone zone) {
        return jdbc.query("""
                        UPDATE zones
                           SET name = :name, parking_allowed = :parking, active = :active,
                               center_lat = :lat, center_lon = :lon, radius_meters = :radius, updated_at = now()
                         WHERE zone_id = :id
                        RETURNING *
                        """,
                params(zone).addValue("active", zone.active()), ROW).stream().findFirst();
    }

    private static MapSqlParameterSource params(Zone zone) {
        return new MapSqlParameterSource()
                .addValue("id", zone.zoneId())
                .addValue("name", zone.name())
                .addValue("parking", zone.parkingAllowed())
                .addValue("lat", zone.centerLat())
                .addValue("lon", zone.centerLon())
                .addValue("radius", zone.radiusMeters());
    }
}
