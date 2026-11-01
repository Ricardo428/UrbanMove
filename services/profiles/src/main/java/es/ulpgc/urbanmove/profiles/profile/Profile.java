package es.ulpgc.urbanmove.profiles.profile;

import java.time.Instant;

/** Perfil de un usuario. phone y language pueden faltar. */
public record Profile(
        String userId,
        String firstName,
        String lastName,
        String phone,
        boolean newsletter,
        String language,
        ProfileStatus status,
        Instant updatedAt) {
}
