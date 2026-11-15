package es.ulpgc.urbanmove.history.entry;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.ulpgc.urbanmove.events.EventJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acceso a history_entries. Solo INSERT y SELECT: el historial es inmutable. */
@Repository
public class HistoryRepository {

    private static final String COLUMNS =
            "event_id, event_type, occurred_at, user_id, vehicle_id, reservation_id, ride_id, details";

    private final JdbcTemplate jdbc;

    public HistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Guarda la entrada. Se ejecuta dentro de la transacción de EventConsumer, junto con processed_events.
     * Si el event_id ya existe, la clave primaria lanza una excepción: es una red de seguridad, porque los
     * duplicados normales ya los descarta processed_events antes de llegar aquí.
     */
    public void save(HistoryEntry entry) {
        jdbc.update("INSERT INTO history_entries (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                entry.eventId(), entry.type(), Timestamp.from(entry.occurredAt()), entry.userId(),
                entry.vehicleId(), entry.reservationId(), entry.rideId(), entry.details().toString());
    }

    /** Lo más reciente primero, por cuándo ocurrió (no por cuándo llegó). Usa el índice history_entries_user. */
    public List<HistoryEntry> findByUser(String userId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM history_entries WHERE user_id = ?"
                + " ORDER BY occurred_at DESC LIMIT ?", HistoryRepository::map, userId, limit);
    }

    /** Igual que findByUser, con el índice history_entries_vehicle. */
    public List<HistoryEntry> findByVehicle(String vehicleId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM history_entries WHERE vehicle_id = ?"
                + " ORDER BY occurred_at DESC LIMIT ?", HistoryRepository::map, vehicleId, limit);
    }

    private static HistoryEntry map(ResultSet rs, int row) throws SQLException {
        try {
            return new HistoryEntry(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("user_id"),
                    rs.getString("vehicle_id"),
                    rs.getString("reservation_id"),
                    rs.getString("ride_id"),
                    EventJson.mapper().readTree(rs.getString("details")));
        } catch (JsonProcessingException e) {
            throw new SQLException("details no es JSON en el evento " + rs.getString("event_id"), e);
        }
    }
}
