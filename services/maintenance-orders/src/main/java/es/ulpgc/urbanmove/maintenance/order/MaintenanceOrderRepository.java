package es.ulpgc.urbanmove.maintenance.order;

import es.ulpgc.urbanmove.events.MaintenanceReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a maintenance_orders. Cada cambio de estado es un UPDATE con el estado de partida (y el operario, si ya
 * lo hay) en el WHERE: si dos operarios compiten por la misma orden, solo uno modifica la fila. Los métodos
 * devuelven si la fila cambió; el servicio decide qué responder si no.
 */
@Repository
public class MaintenanceOrderRepository {

    private static final RowMapper<MaintenanceOrder> ROW = (rs, rowNum) -> new MaintenanceOrder(
            rs.getLong("id"),
            rs.getString("vehicle_id"),
            MaintenanceReason.valueOf(rs.getString("reason")),
            integer(rs, "battery_level_percent"),
            OrderStatus.valueOf(rs.getString("status")),
            task(rs.getString("task")),
            rs.getString("operator_id"),
            rs.getString("zone_id"),
            instant(rs, "detected_at"),
            instant(rs, "created_at"),
            instant(rs, "withdrawn_at"),
            instant(rs, "started_at"),
            instant(rs, "completed_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public MaintenanceOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MaintenanceOrder> find(long id) {
        return jdbc.query("SELECT * FROM maintenance_orders WHERE id = :id", new MapSqlParameterSource("id", id), ROW)
                .stream().findFirst();
    }

    /** Las más antiguas primero. Los dos filtros son opcionales (null = sin filtrar). */
    public List<MaintenanceOrder> list(OrderStatus status, String vehicleId) {
        return jdbc.query("""
                        SELECT * FROM maintenance_orders
                         WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                           AND (CAST(:vehicle AS VARCHAR) IS NULL OR vehicle_id = :vehicle)
                         ORDER BY created_at, id
                        """,
                new MapSqlParameterSource()
                        .addValue("status", status == null ? null : status.name())
                        .addValue("vehicle", vehicleId),
                ROW);
    }

    /**
     * Crea la orden OPEN. Devuelve false si el recurso ya tiene una orden activa: el índice único parcial lo
     * impide y ON CONFLICT DO NOTHING lo convierte en 0 filas, sin error ni transacción rota.
     */
    public boolean insertOpen(String vehicleId, MaintenanceReason reason, Integer batteryLevelPercent,
            Instant detectedAt) {
        return jdbc.update("""
                        INSERT INTO maintenance_orders (vehicle_id, reason, battery_level_percent, status, detected_at)
                        VALUES (:vehicle, :reason, :battery, 'OPEN', :detected)
                        ON CONFLICT DO NOTHING
                        """,
                new MapSqlParameterSource()
                        .addValue("vehicle", vehicleId)
                        .addValue("reason", reason.name())
                        .addValue("battery", batteryLevelPercent)
                        .addValue("detected", Timestamp.from(detectedAt))) == 1;
    }

    /** ¿Tiene el recurso una orden completada después de detectedAt? Entonces la detección llegó tarde. */
    public boolean hasCompletedAfter(String vehicleId, Instant detectedAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM maintenance_orders
                                        WHERE vehicle_id = :vehicle AND status = 'COMPLETED'
                                          AND completed_at > :detected)
                        """,
                new MapSqlParameterSource()
                        .addValue("vehicle", vehicleId)
                        .addValue("detected", Timestamp.from(detectedAt)),
                Boolean.class));
    }

    /** OPEN → WITHDRAWN: la orden queda a nombre del operario. */
    public boolean withdraw(long id, String operatorId, Instant at) {
        return jdbc.update("""
                        UPDATE maintenance_orders
                           SET status = 'WITHDRAWN', operator_id = :operator, withdrawn_at = :at
                         WHERE id = :id AND status = 'OPEN'
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("operator", operatorId)
                        .addValue("at", Timestamp.from(at))) == 1;
    }

    /** WITHDRAWN → IN_PROGRESS, solo si la orden es del mismo operario. */
    public boolean start(long id, String operatorId, MaintenanceTask task, Instant at) {
        return jdbc.update("""
                        UPDATE maintenance_orders
                           SET status = 'IN_PROGRESS', task = :task, started_at = :at
                         WHERE id = :id AND status = 'WITHDRAWN' AND operator_id = :operator
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("operator", operatorId)
                        .addValue("task", task.name())
                        .addValue("at", Timestamp.from(at))) == 1;
    }

    /** IN_PROGRESS → COMPLETED, solo si la orden es del mismo operario. La orden sale del índice de activas. */
    public boolean complete(long id, String operatorId, String zoneId, Instant at) {
        return jdbc.update("""
                        UPDATE maintenance_orders
                           SET status = 'COMPLETED', zone_id = :zone, completed_at = :at
                         WHERE id = :id AND status = 'IN_PROGRESS' AND operator_id = :operator
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("operator", operatorId)
                        .addValue("zone", zoneId)
                        .addValue("at", Timestamp.from(at))) == 1;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static MaintenanceTask task(String value) {
        return value == null ? null : MaintenanceTask.valueOf(value);
    }
}
