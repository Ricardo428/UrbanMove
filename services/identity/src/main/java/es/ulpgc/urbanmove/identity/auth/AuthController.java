package es.ulpgc.urbanmove.identity.auth;

import es.ulpgc.urbanmove.common.error.ApiException;
import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.common.security.InvalidTokenException;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.identity.user.UserEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity")
public class AuthController {

    private static final String BEARER = "Bearer ";

    private final AuthService auth;
    private final JwtTokenService tokens;

    public AuthController(AuthService auth, JwtTokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    /** Público: el rol siempre es USER, el cliente no puede elegirlo. */
    @PostMapping("/register")
    public ResponseEntity<UserCreatedResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserEntity user = auth.createUser(request.email(), request.password(), Role.USER);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserCreatedResponse(user.publicId(), user.getRole().name(), "Usuario registrado correctamente"));
    }

    /** Solo ADMIN: es la única forma de obtener los roles MAINTENANCE y ADMIN. */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserCreatedResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserEntity user = auth.createUser(request.email(), request.password(), request.role());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserCreatedResponse(user.publicId(), user.getRole().name(), "Usuario creado correctamente"));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }

    /** Respaldo para los demás servicios: la ruta es pública y comprueba el token a mano. */
    @GetMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw ApiException.badRequest("Falta la cabecera Authorization");
        }
        if (!authorization.startsWith(BEARER)) {
            return unauthorized();
        }
        try {
            AuthenticatedUser user = tokens.parse(authorization.substring(BEARER.length()).trim());
            return ResponseEntity.ok(ValidateResponse.ok(user.userId(), user.role().name()));
        } catch (InvalidTokenException e) {
            return unauthorized();
        }
    }

    private static ResponseEntity<ValidateResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ValidateResponse.invalid("Token expirado o inválido"));
    }
}
