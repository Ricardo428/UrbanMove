package es.ulpgc.urbanmove.relocation.order;

import es.ulpgc.urbanmove.events.VehicleType;
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
 * Acceso a relocation_orders y relocation_order_vehicles (V1__create_relocation_orders.sql). Solo este servicio
 * escribe en estas tablas. Cada cambio de estado es un UPDATE con el estado de partida en el WHERE: si dos
 * peticiones compiten por la misma orden, solo una modifica la fila. Los métodos devuelven si la fila cambió; el
 * servicio decide qué responder si no.
 */
@Repository
public class RelocationOrderRepository {

    private static final RowMapper<RelocationOrder> ROW = (rs, rowNum) -> new RelocationOrder(
            rs.getLong("id"),
            rs.getString("from_zone_id"),
            rs.getString("to_zone_id"),
            VehicleType.valueOf(rs.getString("vehicle_type")),
            rs.getInt("quantity"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getString("operator_id"),
            instant(rs, "created_at"),
            instant(rs, "picked_up_at"),
            instant(rs, "completed_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public RelocationOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta una orden OPEN. Si ya hay una orden OPEN o IN_PROGRESS para el mismo destino y tipo, el índice
     * relocation_orders_one_active_per_zone choca, ON CONFLICT DO NOTHING no inserta nada y RETURNING no devuelve
     * ninguna fila. Con dos réplicas a la vez, la segunda espera a que la primera termine y también obtiene 0 filas.
     *
     * @return el id de la orden creada, o vacío si ya había una activa
     */
    public Optional<Long> insertOpen(String fromZoneId, String toZoneId, VehicleType vehicleType, int quantity,
            Instant createdAt) {
        List<Long> ids = jdbc.queryForList("""
                        INSERT INTO relocation_orders (from_zone_id, to_zone_id, vehicle_type, quantity, status, created_at)
                        VALUES (:from, :to, :type, :quantity, 'OPEN', :created)
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("from", fromZoneId)
                        .addValue("to", toZoneId)
                        .addValue("type", vehicleType.name())
                        .addValue("quantity", quantity)
                        .addValue("created", Timestamp.from(createdAt)),
                Long.class);
        return ids.stream().findFirst();
    }

    public Optional<RelocationOrder> find(long id) {
        return jdbc.query("SELECT * FROM relocation_orders WHERE id = :id", new MapSqlParameterSource("id", id), ROW)
                .stream().findFirst();
    }

    /** Las más antiguas primero. status es opcional (null = todas). */
    public List<RelocationOrder> list(OrderStatus status) {
        return jdbc.query("""
                        SELECT * FROM relocation_orders
                         WHERE (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                         ORDER BY created_at, id
                        """,
                new MapSqlParameterSource("status", status == null ? null : status.name()),
                ROW);
    }

    /** Recursos reclamados por la orden, en el orden en que se reclamaron. Incluye los de una orden completada. */
    public List<String> vehicleIds(long orderId) {
        return jdbc.queryForList("""
                        SELECT vehicle_id FROM relocation_order_vehicles
                         WHERE order_id = :order
                         ORDER BY claimed_at, vehicle_id
                        """,
                new MapSqlParameterSource("order", orderId), String.class);
    }

    /** OPEN → CANCELLED. false si la orden ya no estaba OPEN. */
    public boolean cancel(long id) {
        return jdbc.update("UPDATE relocation_orders SET status = 'CANCELLED' WHERE id = :id AND status = 'OPEN'",
                new MapSqlParameterSource("id", id)) == 1;
    }

    /**
     * OPEN → IN_PROGRESS con el operario del token. Si dos operarios lo piden a la vez, el segundo espera al bloqueo
     * de fila del primero, vuelve a evaluar el WHERE, ya no ve OPEN y cambia 0 filas: false.
     */
    public boolean claim(long id, String operatorId, Instant pickedUpAt) {
        return jdbc.update("""
                        UPDATE relocation_orders
                           SET status = 'IN_PROGRESS', operator_id = :operator, picked_up_at = :at
                         WHERE id = :id AND status = 'OPEN'
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("operator", operatorId)
                        .addValue("at", Timestamp.from(pickedUpAt))) == 1;
    }

    /**
     * Reclama un recurso para la orden ANTES de pedir el WITHDRAWN a availability. Si otra orden activa ya lo tiene,
     * el índice relocation_order_vehicles_one_active_order choca y no se inserta nada: false. Hace falta porque el
     * PUT /state es idempotente y no distingue "lo retiró esta orden" de "lo retiró otra".
     */
    public boolean claimVehicle(long orderId, String vehicleId, Instant claimedAt) {
        return jdbc.update("""
                        INSERT INTO relocation_order_vehicles (order_id, vehicle_id, claimed_at)
                        VALUES (:order, :vehicle, :at)
                        ON CONFLICT DO NOTHING
                        """,
                new MapSqlParameterSource()
                        .addValue("order", orderId)
                        .addValue("vehicle", vehicleId)
                        .addValue("at", Timestamp.from(claimedAt))) == 1;
    }

    /** Suelta un recurso que availability rechazó: ya puede entrar en otra orden. */
    public void releaseVehicle(long orderId, String vehicleId) {
        jdbc.update("DELETE FROM relocation_order_vehicles WHERE order_id = :order AND vehicle_id = :vehicle",
                new MapSqlParameterSource().addValue("order", orderId).addValue("vehicle", vehicleId));
    }

    /**
     * IN_PROGRESS → OPEN cuando el pickup no retiró ningún recurso. Solo si es del mismo operario y no le queda
     * ningún recurso reclamado: una orden con un recurso que quizá está WITHDRAWN no puede volver a OPEN, porque
     * entonces se podría cancelar con ese recurso fuera de servicio.
     */
    public boolean reopen(long id, String operatorId) {
        return jdbc.update("""
                        UPDATE relocation_orders
                           SET status = 'OPEN', operator_id = NULL, picked_up_at = NULL
                         WHERE id = :id AND status = 'IN_PROGRESS' AND operator_id = :operator
                           AND NOT EXISTS (SELECT 1 FROM relocation_order_vehicles WHERE order_id = :id)
                        """,
                new MapSqlParameterSource().addValue("id", id).addValue("operator", operatorId)) == 1;
    }

    /**
     * IN_PROGRESS → COMPLETED, solo del operario que la recogió. Cambia una fila una sola vez: si dos peticiones lo
     * intentan a la vez, la segunda vuelve a evaluar el WHERE tras el bloqueo, ya no ve IN_PROGRESS y devuelve false.
     * Por eso nunca hay dos RelocationOrderCompleted de la misma orden.
     */
    public boolean complete(long id, String operatorId, Instant completedAt) {
        return jdbc.update("""
                        UPDATE relocation_orders
                           SET status = 'COMPLETED', completed_at = :at
                         WHERE id = :id AND status = 'IN_PROGRESS' AND operator_id = :operator
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("operator", operatorId)
                        .addValue("at", Timestamp.from(completedAt))) == 1;
    }

    /** Los recursos de una orden completada salen del índice de recursos activos: pueden entrar en otra orden. */
    public void deactivateVehicles(long orderId) {
        jdbc.update("UPDATE relocation_order_vehicles SET active = false WHERE order_id = :order",
                new MapSqlParameterSource("order", orderId));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
