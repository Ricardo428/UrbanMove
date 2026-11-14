package es.ulpgc.urbanmove.identity.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Deshabilita al usuario con una sola sentencia condicionada, así que repetirla no cambia nada.
     * Devuelve las filas afectadas: 0 si no existe o ya estaba deshabilitado.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserEntity u SET u.enabled = false WHERE u.id = :id AND u.enabled = true")
    int disable(@Param("id") Long id);
}
