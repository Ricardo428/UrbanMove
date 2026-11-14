package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Baja de usuario. Lo emite profiles al desactivar un perfil; identity bloquea el acceso de ese usuario. */
public record UserDeactivated(
        String userId,
        Instant deactivatedAt) implements EventPayload {
}
