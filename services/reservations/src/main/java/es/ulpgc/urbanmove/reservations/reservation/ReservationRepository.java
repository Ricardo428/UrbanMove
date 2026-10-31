package es.ulpgc.urbanmove.reservations.reservation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a reservations. Cada cambio de estado es un UPDATE con el estado de partida en el WHERE: si dos
 * operaciones compiten (por ejemplo, cancelar y expirar a la vez), solo una modifica la fila. Los métodos
 * devuelven si la fila cambió; el servicio decide qué responder si no.
 */
@Repository
public class ReservationRepository {

    private static final RowMapper<Reservation> ROW = (rs, rowNum) -> new Reservation(
            rs.getLong("id"),
            rs.getString("vehicle_id"),
            rs.getString("user_id"),
            rs.getString("zone_id"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ReservationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Reservation> find(long id) {
        return jdbc.query("SELECT * FROM reservations WHERE id = :id", new MapSqlParameterSource("id", id), ROW)
                .stream().findFirst();
    }

    /** Guarda una reserva nueva en PENDING y devuelve su id. */
    public long insertPending(String vehicleId, String userId, Instant createdAt, Instant expiresAt) {
        return jdbc.queryForObject("""
                        INSERT INTO reservations (vehicle_id, user_id, status, created_at, expires_at)
                        VALUES (:vehicle, :user, 'PENDING', :created, :expires)
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("vehicle", vehicleId)
                        .addValue("user", userId)
                        .addValue("created", Timestamp.from(createdAt))
                        .addValue("expires", Timestamp.from(expiresAt)),
                Long.class);
    }

    /** Availability aceptó la reserva: se guarda la zona en la que está el vehículo. Sigue PENDING. */
    public boolean setZone(long id, String zoneId) {
        return jdbc.update("UPDATE reservations SET zone_id = :zone WHERE id = :id AND status = 'PENDING'",
                new MapSqlParameterSource().addValue("id", id).addValue("zone", zoneId)) == 1;
    }

    /** PENDING → REJECTED: availability no pudo reservar el vehículo. */
    public boolean reject(long id) {
        return jdbc.update("UPDATE reservations SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING'",
                new MapSqlParameterSource("id", id)) == 1;
    }

    /** PENDING → CONFIRMED, solo si aún no ha vencido. El plazo pasa a contar desde la confirmación. */
    public boolean confirm(long id, Instant now, Instant newExpiresAt) {
        return jdbc.update("""
                        UPDATE reservations SET status = 'CONFIRMED', expires_at = :expires
                         WHERE id = :id AND status = 'PENDING' AND expires_at > :now
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", Timestamp.from(now))
                        .addValue("expires", Timestamp.from(newExpiresAt))) == 1;
    }

    /** PENDING o CONFIRMED → CANCELLED. */
    public boolean cancel(long id) {
        return jdbc.update("""
                        UPDATE reservations SET status = 'CANCELLED'
                         WHERE id = :id AND status IN ('PENDING', 'CONFIRMED')
                        """,
                new MapSqlParameterSource("id", id)) == 1;
    }

    /** PENDING o CONFIRMED → EXPIRED, solo si ya ha vencido. */
    public boolean expire(long id, Instant now) {
        return jdbc.update("""
                        UPDATE reservations SET status = 'EXPIRED'
                         WHERE id = :id AND status IN ('PENDING', 'CONFIRMED') AND expires_at <= :now
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", Timestamp.from(now))) == 1;
    }

    /** PENDING o CONFIRMED → USED: el usuario ha iniciado el viaje con esta reserva. */
    public boolean markUsed(long id) {
        return jdbc.update("""
                        UPDATE reservations SET status = 'USED'
                         WHERE id = :id AND status IN ('PENDING', 'CONFIRMED')
                        """,
                new MapSqlParameterSource("id", id)) == 1;
    }
}
