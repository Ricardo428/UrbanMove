package es.ulpgc.urbanmove.profiles.profile;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.profiles.profile.ProfileRepository.Saved;
import org.springframework.stereotype.Service;

/** Cada usuario solo accede a su propio perfil: el userId siempre sale del token, nunca de la petición. */
@Service
public class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
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

    public void deactivate(String userId) {
        if (profiles.deactivate(userId)) {
            return;
        }
        if (profiles.find(userId).isEmpty()) {
            throw ApiException.notFound("El usuario no tiene perfil");
        }
        throw ApiException.conflict("El perfil ya estaba dado de baja");
    }
}
