package es.ulpgc.urbanmove.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración de seguridad de cada servicio (prefijo urbanmove.security).
 *
 * @param jwtSecret            clave de firma HS256, de al menos 32 caracteres, por variable de entorno
 * @param jwtExpirationSeconds validez del token; solo la usa identity al emitirlo
 * @param publicPaths          rutas sin token, además de /actuator/health y /actuator/info
 */
@ConfigurationProperties(prefix = "urbanmove.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("3600") long jwtExpirationSeconds,
        @DefaultValue List<String> publicPaths) {
}
