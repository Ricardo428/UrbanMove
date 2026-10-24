package es.ulpgc.urbanmove.identity.auth;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.identity.user.UserEntity;
import es.ulpgc.urbanmove.identity.user.UserRepository;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtTokenService tokens;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtTokenService tokens) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    public UserEntity createUser(String email, String password, Role role) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("El email ya está registrado");
        }
        try {
            return users.saveAndFlush(new UserEntity(normalized, encoder.encode(password), role));
        } catch (DataIntegrityViolationException e) {
            // Dos altas simultáneas con el mismo email: gana la restricción UNIQUE de la base de datos.
            throw ApiException.conflict("El email ya está registrado");
        }
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
