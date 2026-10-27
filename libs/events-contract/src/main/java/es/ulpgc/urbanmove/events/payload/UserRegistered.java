package es.ulpgc.urbanmove.events.payload;

import es.ulpgc.urbanmove.events.EventPayload;
import java.time.Instant;

/** Alta de usuario. Lo emite identity; role es USER, MAINTENANCE o ADMIN. */
public record UserRegistered(
        String userId,
        String role,
        Instant registeredAt) implements EventPayload {
}
