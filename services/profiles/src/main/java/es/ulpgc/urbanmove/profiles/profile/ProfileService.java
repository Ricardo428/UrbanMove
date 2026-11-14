package es.ulpgc.urbanmove.profiles.profile;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.events.payload.UserDeactivated;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import es.ulpgc.urbanmove.profiles.profile.ProfileRepository.Saved;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cada usuario solo accede a su propio perfil: el userId siempre sale del token, nunca de la petición. */
@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final EventPublisher events;

    public ProfileService(ProfileRepository profiles, EventPublisher events) {
        this.profiles = profiles;
        this.events = events;
    }

    public Saved save(String userId, String firstName, String lastName, String phone, boolean newsletter,
            String language) {
        return profiles.upsert(userId, firstName.trim(), lastName.trim(), phone, newsletter, language)
                .orElseThrow(() -> ApiException.forbidden("El perfil está dado de baja y no admite cambios"));
    }

    public Profile find(String userId) {
        return profiles.find(userId)
                .orElseThrow(() -> ApiException.notFound("El usuario todavía no tiene perfil"));
    }

    /**
     * Da de baja el perfil y deja UserDeactivated en la outbox, en la misma transacción: identity bloquea el
     * acceso del usuario al recibirlo. Si el perfil no se puede dar de baja no se publica nada.
     */
    @Transactional
    public void deactivate(String userId) {
        if (profiles.deactivate(userId)) {
            events.publish(new UserDeactivated(userId, Instant.now()));
            return;
        }
        if (profiles.find(userId).isEmpty()) {
            throw ApiException.notFound("El usuario no tiene perfil");
        }
        throw ApiException.conflict("El perfil ya estaba dado de baja");
    }
}
