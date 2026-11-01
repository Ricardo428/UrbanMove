package es.ulpgc.urbanmove.profiles.profile;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileRepository {

    private static final RowMapper<Profile> ROW = (rs, rowNum) -> new Profile(
            rs.getString("user_id"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("phone"),
            rs.getBoolean("newsletter"),
            rs.getString("language"),
            ProfileStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("updated_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public ProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resultado de guardar: el perfil como queda y si se ha creado ahora. */
    public record Saved(Profile profile, boolean created) {
    }

    public Optional<Profile> find(String userId) {
        return jdbc.query("SELECT * FROM profiles WHERE user_id = :user", new MapSqlParameterSource("user", userId),
                ROW).stream().findFirst();
    }

    /**
     * Crea el perfil o lo actualiza, en una sola sentencia: dos PUT simultáneos del mismo usuario no pueden crear
     * dos perfiles. Si el perfil existe pero está INACTIVE, el WHERE impide la actualización y no devuelve nada.
     * xmax vale 0 solo en una fila recién insertada, y así se distingue crear de actualizar.
     */
    public Optional<Saved> upsert(String userId, String firstName, String lastName, String phone,
            boolean newsletter, String language) {
        return jdbc.query("""
                        INSERT INTO profiles (user_id, first_name, last_name, phone, newsletter, language)
                        VALUES (:user, :first, :last, :phone, :newsletter, :language)
                        ON CONFLICT (user_id) DO UPDATE
                           SET first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                               phone = EXCLUDED.phone, newsletter = EXCLUDED.newsletter,
                               language = EXCLUDED.language, updated_at = now()
                         WHERE profiles.status = 'ACTIVE'
                        RETURNING *, (xmax = 0) AS created
                        """,
                new MapSqlParameterSource()
                        .addValue("user", userId)
                        .addValue("first", firstName)
                        .addValue("last", lastName)
                        .addValue("phone", phone)
                        .addValue("newsletter", newsletter)
                        .addValue("language", language),
                (rs, rowNum) -> new Saved(ROW.mapRow(rs, rowNum), rs.getBoolean("created")))
                .stream().findFirst();
    }

    /** ACTIVE → INACTIVE. Devuelve false si no había perfil activo (no existe o ya estaba de baja). */
    public boolean deactivate(String userId) {
        return jdbc.update("""
                        UPDATE profiles SET status = 'INACTIVE', updated_at = now()
                         WHERE user_id = :user AND status = 'ACTIVE'
                        """,
                new MapSqlParameterSource("user", userId)) == 1;
    }
}
