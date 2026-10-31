package es.ulpgc.urbanmove.availability.vehicle;

import es.ulpgc.urbanmove.events.VehicleType;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a vehicle_state. Todo cambio de estado es un UPDATE con la condición sobre el estado actual en el WHERE:
 * PostgreSQL serializa las escrituras sobre la misma fila, así que de dos peticiones simultáneas solo una
 * modifica la fila (ADR-002). Los métodos devuelven si la fila cambió; el servicio decide qué responder si no.
 */
@Repository
public class VehicleStateRepository {

    private static final RowMapper<VehicleState> ROW = (rs, rowNum) -> new VehicleState(
            rs.getString("vehicle_id"),
            VehicleType.valueOf(rs.getString("vehicle_type")),
            rs.getString("zone_id"),
            VehicleStatus.valueOf(rs.getString("status")),
            rs.getString("reservation_id"),
            rs.getString("ride_id"));

    private final NamedParameterJdbcTemplate jdbc;

    public VehicleStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VehicleState> find(String vehicleId) {
        return jdbc.query("SELECT * FROM vehicle_state WHERE vehicle_id = :id",
                new MapSqlParameterSource("id", vehicleId), ROW).stream().findFirst();
    }

    /** Recursos AVAILABLE; zoneId y type son filtros opcionales. */
    public List<VehicleState> findAvailable(String zoneId, VehicleType type) {
        return jdbc.query("""
                        SELECT * FROM vehicle_state
                         WHERE status = 'AVAILABLE'
                           AND (CAST(:zone AS VARCHAR) IS NULL OR zone_id = :zone)
                           AND (CAST(:type AS VARCHAR) IS NULL OR vehicle_type = :type)
                         ORDER BY vehicle_id
                        """,
                new MapSqlParameterSource()
                        .addValue("zone", zoneId)
                        .addValue("type", type == null ? null : type.name()),
                ROW);
    }

    /** Alta de un recurso nuevo en AVAILABLE. Si ya existe no hace nada (VehiclePurchased repetido). */
    public boolean insertAvailable(String vehicleId, VehicleType type, String zoneId) {
        return jdbc.update("""
                        INSERT INTO vehicle_state (vehicle_id, vehicle_type, zone_id, status)
                        VALUES (:id, :type, :zone, 'AVAILABLE')
                        ON CONFLICT (vehicle_id) DO NOTHING
                        """,
                new MapSqlParameterSource()
                        .addValue("id", vehicleId)
                        .addValue("type", type.name())
                        .addValue("zone", zoneId)) == 1;
    }

    /** AVAILABLE → RESERVED. */
    public boolean reserve(String vehicleId, String reservationId) {
        return jdbc.update("""
                        UPDATE vehicle_state
                           SET status = 'RESERVED', reservation_id = :reservation, updated_at = now()
                         WHERE vehicle_id = :id AND status = 'AVAILABLE'
                        """,
                new MapSqlParameterSource()
                        .addValue("id", vehicleId)
                        .addValue("reservation", reservationId)) == 1;
    }

    /**
     * AVAILABLE → IN_USE, o RESERVED → IN_USE si la reserva que lo tiene es reservationId.
     * Con reservationId null solo se acepta un recurso AVAILABLE.
     */
    public boolean start(String vehicleId, String rideId, String reservationId) {
        return jdbc.update("""
                        UPDATE vehicle_state
                           SET status = 'IN_USE', ride_id = :ride, reservation_id = NULL, updated_at = now()
                         WHERE vehicle_id = :id
                           AND (status = 'AVAILABLE'
                                OR (status = 'RESERVED' AND reservation_id = CAST(:reservation AS VARCHAR)))
                        """,
                new MapSqlParameterSource()
                        .addValue("id", vehicleId)
                        .addValue("ride", rideId)
                        .addValue("reservation", reservationId)) == 1;
    }

    /** RESERVED → AVAILABLE, solo si la reserva que lo tiene es reservationId (cancelación o caducidad). */
    public boolean release(String vehicleId, String reservationId) {
        return jdbc.update("""
                        UPDATE vehicle_state
                           SET status = 'AVAILABLE', reservation_id = NULL, updated_at = now()
                         WHERE vehicle_id = :id AND status = 'RESERVED' AND reservation_id = :reservation
                        """,
                new MapSqlParameterSource()
                        .addValue("id", vehicleId)
                        .addValue("reservation", reservationId)) == 1;
    }

    /** IN_USE → AVAILABLE o LOW_BATTERY al terminar el viaje rideId, dejando el recurso en la zona de destino. */
    public boolean finishRide(String vehicleId, String rideId, VehicleStatus newStatus, String destinationZoneId) {
        return jdbc.update("""
                        UPDATE vehicle_state
                           SET status = :status, zone_id = :zone, ride_id = NULL, updated_at = now()
                         WHERE vehicle_id = :id AND status = 'IN_USE' AND ride_id = :ride
                        """,
                new MapSqlParameterSource()
                        .addValue("id", vehicleId)
                        .addValue("ride", rideId)
                        .addValue("status", newStatus.name())
                        .addValue("zone", destinationZoneId)) == 1;
    }

    /**
     * Cambio de estado pedido por PUT /state. La condición sobre el estado leído evita pisar un cambio
     * concurrente: si otro lo movió entre la lectura y la escritura, no cambia nada. zoneId null mantiene la zona.
     */
    public boolean changeStatus(String vehicleId, VehicleStatus expected, VehicleStatus target, String zoneId) {
        return jdbc.update("""
                        UPDATE vehicle_state
                           SET status = :target, zone_id = COALESCE(CAST(:zone AS VARCHAR), zone_id), updated_at = now()
                         WHERE vehicle_id = :id AND status = :expected
                        """,
                new MapSqlParameterSource()
                        .addValue("id", vehicleId)
                        .addValue("expected", expected.name())
                        .addValue("target", target.name())
                        .addValue("zone", zoneId)) == 1;
    }
}
