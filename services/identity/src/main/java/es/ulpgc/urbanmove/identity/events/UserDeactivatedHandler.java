package es.ulpgc.urbanmove.identity.events;

import es.ulpgc.urbanmove.events.EventEnvelope;
import es.ulpgc.urbanmove.events.payload.UserDeactivated;
import es.ulpgc.urbanmove.identity.user.UserIds;
import es.ulpgc.urbanmove.identity.user.UserRepository;
import es.ulpgc.urbanmove.messaging.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Profiles ha dado de baja a un usuario: se le deshabilita la cuenta y deja de poder iniciar sesión.
 * Un usuario desconocido se registra y se ignora: reintentarlo no lo haría aparecer y el mensaje acabaría
 * en la dead-letter queue sin remedio.
 */
@Component
public class UserDeactivatedHandler implements EventHandler<UserDeactivated> {

    private static final Logger log = LoggerFactory.getLogger(UserDeactivatedHandler.class);

    private final UserRepository users;

    public UserDeactivatedHandler(UserRepository users) {
        this.users = users;
    }

    @Override
    public Class<UserDeactivated> payloadType() {
        return UserDeactivated.class;
    }

    @Override
    public void handle(UserDeactivated payload, EventEnvelope event) {
        Long id = UserIds.parse(payload.userId()).orElse(null);
        if (id == null || users.disable(id) == 0) {
            log.info("UserDeactivated {}: el usuario {} no existe o ya estaba deshabilitado", event.eventId(),
                    payload.userId());
            return;
        }
        log.info("Usuario {} deshabilitado por baja en profiles", payload.userId());
    }
}
