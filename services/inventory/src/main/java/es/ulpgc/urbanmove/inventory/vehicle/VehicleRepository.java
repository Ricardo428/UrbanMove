package es.ulpgc.urbanmove.inventory.vehicle;

import es.ulpgc.urbanmove.events.VehicleType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VehicleRepository {

    private static final RowMapper<Vehicle> ROW = (rs, rowNum) -> new Vehicle(
            rs.getLong("id"),
            VehicleType.valueOf(rs.getString("vehicle_type")),
            rs.getString("serial_number"),
            rs.getString("model"),
            rs.getObject("purchase_date", LocalDate.class),
            AdministrativeStatus.valueOf(rs.getString("administrative_status")));

    private final NamedParameterJdbcTemplate jdbc;

    public VehicleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Vehicle> find(long id) {
        return jdbc.query("SELECT * FROM vehicles WHERE id = :id", new MapSqlParameterSource("id", id), ROW)
                .stream().findFirst();
    }

    /**
     * Inserta un recurso ACTIVE. Si el número de serie ya existe no inserta nada y devuelve vacío; con dos altas
     * simultáneas del mismo número de serie, la segunda espera a la primera y también devuelve vacío.
     */
    public Optional<Vehicle> insert(VehicleType type, String serialNumber, String model, LocalDate purchaseDate) {
        return jdbc.query("""
                        INSERT INTO vehicles (vehicle_type, serial_number, model, purchase_date)
                        VALUES (:type, :serial, :model, :purchaseDate)
                        ON CONFLICT (serial_number) DO NOTHING
                        RETURNING *
                        """,
                new MapSqlParameterSource()
                        .addValue("type", type.name())
                        .addValue("serial", serialNumber)
                        .addValue("model", model)
                        .addValue("purchaseDate", purchaseDate),
                ROW).stream().findFirst();
    }

    /** Los dos filtros son opcionales; null significa "sin filtrar". */
    public List<Vehicle> list(VehicleType type, AdministrativeStatus status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM vehicles WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (type != null) {
            sql.append(" AND vehicle_type = :type");
            params.addValue("type", type.name());
        }
        if (status != null) {
            sql.append(" AND administrative_status = :status");
            params.addValue("status", status.name());
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), params, ROW);
    }
}
