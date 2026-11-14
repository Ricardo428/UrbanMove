package es.ulpgc.urbanmove.identity.user;

import java.util.Optional;

/** Convierte el identificador público (u-1001) en la clave de la tabla users. */
public final class UserIds {

    private static final String PREFIX = "u-";

    private UserIds() {
    }

    public static Optional<Long> parse(String publicId) {
        if (publicId == null || !publicId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(publicId.substring(PREFIX.length())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
