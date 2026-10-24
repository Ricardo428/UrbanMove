package es.ulpgc.urbanmove.identity.config;

import es.ulpgc.urbanmove.common.security.Role;
import es.ulpgc.urbanmove.identity.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Crea el primer ADMIN al arrancar si BOOTSTRAP_ADMIN_EMAIL y BOOTSTRAP_ADMIN_PASSWORD están definidas
 * y ese usuario no existe. Hace falta porque solo un ADMIN puede crear otros ADMIN o MAINTENANCE.
 * Las credenciales llegan por variables de entorno, nunca en el repositorio.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthService auth;
    private final String email;
    private final String password;

    public AdminBootstrap(AuthService auth,
            @Value("${urbanmove.identity.bootstrap-admin.email:}") String email,
            @Value("${urbanmove.identity.bootstrap-admin.password:}") String password) {
        this.auth = auth;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.info("Sin BOOTSTRAP_ADMIN_EMAIL y BOOTSTRAP_ADMIN_PASSWORD: no se crea ningún ADMIN inicial");
            return;
        }
        if (password.length() < 8) {
            log.warn("BOOTSTRAP_ADMIN_PASSWORD tiene menos de 8 caracteres: no se crea el ADMIN inicial");
            return;
        }
        if (auth.emailExists(email)) {
            log.info("El ADMIN inicial ya existe");
            return;
        }
        auth.createUser(email, password, Role.ADMIN);
        log.info("ADMIN inicial creado con el email {}", email);
    }
}
