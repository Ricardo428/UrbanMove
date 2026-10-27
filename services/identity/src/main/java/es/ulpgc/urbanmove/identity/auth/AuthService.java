package es.ulpgc.urbanmove.identity.auth;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.events.payload.UserRegistered;
import es.ulpgc.urbanmove.identity.user.UserEntity;
import es.ulpgc.urbanmove.identity.user.UserRepository;
import es.ulpgc.urbanmove.messaging.EventPublisher;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;
    private final EventPublisher events;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtTokenService tokens,
            EventPublisher events) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.events = events;
    }

    /** Crea el usuario y deja UserRegistered en la outbox, en la misma transacción. */
    @Transactional
    public UserEntity createUser(String email, String password, Role role) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("El email ya está registrado");
        }
        UserEntity user;
        try {
            user = users.saveAndFlush(new UserEntity(normalized, encoder.encode(password), role));
        } catch (DataIntegrityViolationException e) {
            // Dos altas simultáneas con el mismo email: gana la restricción UNIQUE de la base de datos.
            throw ApiException.conflict("El email ya está registrado");
        }
        events.publish(new UserRegistered(user.publicId(), role.name(), Instant.now()));
        return user;
    }

    public LoginResponse login(String email, String password) {
        UserEntity user = users.findByEmail(normalize(email))
                .filter(found -> encoder.matches(password, found.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Credenciales incorrectas"));
        String token = tokens.generate(user.publicId(), user.getRole());
        return new LoginResponse(token, user.getRole().name(), tokens.getExpirationSeconds());
    }

    public boolean emailExists(String email) {
        return users.existsByEmail(normalize(email));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
