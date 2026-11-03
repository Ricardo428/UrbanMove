package es.ulpgc.urbanmove.rides.ride;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a rides. Cada cambio de estado es un UPDATE con el estado de partida en el WHERE: si dos
 * operaciones compiten (por ejemplo, dos finalizaciones a la vez), solo una modifica la fila. Los métodos
 * devuelven si la fila cambió; el servicio decide qué responder si no.
 */
@Repository
public class RideRepository {

    private static final RowMapper<Ride> ROW = (rs, rowNum) -> new Ride(
            rs.getLong("id"),
            rs.getString("vehicle_id"),
            rs.getString("user_id"),
            rs.getString("reservation_id"),
            RideStatus.valueOf(rs.getString("status")),
            rs.getString("origin_zone_id"),
            rs.getTimestamp("start_time").toInstant(),
            rs.getString("destination_zone_id"),
            toInstant(rs.getTimestamp("end_time")),
            rs.getObject("duration_minutes", Integer.class),
            rs.getObject("zone_validated", Boolean.class),
            rs.getBigDecimal("preliminary_cost"),
            rs.getObject("battery_level_percent", Integer.class));

    private final NamedParameterJdbcTemplate jdbc;

    public RideRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Ride> find(long id) {
        return jdbc.query("SELECT * FROM rides WHERE id = :id", new MapSqlParameterSource("id", id), ROW)
                .stream().findFirst();
    }

    /** Guarda un viaje nuevo en STARTING, antes de llamar a availability, y devuelve su id. */
    public long insertStarting(String vehicleId, String userId, String reservationId, Instant startTime) {
        return jdbc.queryForObject("""
                        INSERT INTO rides (vehicle_id, user_id, reservation_id, status, start_time)
                        VALUES (:vehicle, :user, :reservation, 'STARTING', :start)
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("vehicle", vehicleId)
                        .addValue("user", userId)
                        .addValue("reservation", reservationId)
                        .addValue("start", Timestamp.from(startTime)),
                Long.class);
    }

    /**
     * Un viaje que se quedó STARTING porque availability no respondió, del mismo usuario, vehículo y reserva.
     * Se reutiliza al reintentar para que availability reciba el mismo rideId (su start es idempotente por él).
     * IS NOT DISTINCT FROM trata dos reservas null como iguales; con = el resultado sería desconocido.
     */
    public Optional<Ride> findStarting(String userId, String vehicleId, String reservationId) {
        return jdbc.query("""
                        SELECT * FROM rides
                         WHERE user_id = :user AND vehicle_id = :vehicle
                           AND reservation_id IS NOT DISTINCT FROM :reservation
                           AND status = 'STARTING'
                         ORDER BY id DESC
                         LIMIT 1
                        """,
                new MapSqlParameterSource()
                        .addValue("user", userId)
                        .addValue("vehicle", vehicleId)
                        .addValue("reservation", reservationId, Types.VARCHAR),
                ROW).stream().findFirst();
    }

    /** STARTING → IN_PROGRESS: availability puso el vehículo IN_USE y dijo en qué zona estaba. */
    public boolean markInProgress(long id, String originZoneId) {
        return jdbc.update("""
                        UPDATE rides SET status = 'IN_PROGRESS', origin_zone_id = :zone
                         WHERE id = :id AND status = 'STARTING'
                        """,
                new MapSqlParameterSource().addValue("id", id).addValue("zone", originZoneId)) == 1;
    }

    /** STARTING → REJECTED: availability no aceptó el vehículo (409 o 404). */
    public boolean reject(long id) {
        return jdbc.update("UPDATE rides SET status = 'REJECTED' WHERE id = :id AND status = 'STARTING'",
                new MapSqlParameterSource("id", id)) == 1;
    }

    /** IN_PROGRESS → COMPLETED, guardando los datos del final del viaje. */
    public boolean complete(long id, String destinationZoneId, Instant endTime, int durationMinutes,
            boolean zoneValidated, BigDecimal preliminaryCost, int batteryLevelPercent) {
        return jdbc.update("""
                        UPDATE rides SET status = 'COMPLETED',
                                         destination_zone_id = :destination,
                                         end_time = :end,
                                         duration_minutes = :duration,
                                         zone_validated = :validated,
                                         preliminary_cost = :cost,
                                         battery_level_percent = :battery
                         WHERE id = :id AND status = 'IN_PROGRESS'
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("destination", destinationZoneId)
                        .addValue("end", Timestamp.from(endTime))
                        .addValue("duration", durationMinutes)
                        .addValue("validated", zoneValidated)
                        .addValue("cost", preliminaryCost)
                        .addValue("battery", batteryLevelPercent)) == 1;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
