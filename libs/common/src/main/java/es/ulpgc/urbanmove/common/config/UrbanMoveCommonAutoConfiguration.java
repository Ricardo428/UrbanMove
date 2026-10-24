package es.ulpgc.urbanmove.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ulpgc.urbanmove.common.error.ErrorResponse;
import es.ulpgc.urbanmove.common.error.GlobalExceptionHandler;
import es.ulpgc.urbanmove.common.security.JwtAuthenticationFilter;
import es.ulpgc.urbanmove.common.security.JwtTokenService;
import es.ulpgc.urbanmove.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Se activa sola en cualquier servicio que dependa de "common": X-Correlation-Id, formato de error común
 * y validación local del JWT. Cada servicio declara sus rutas públicas con urbanmove.security.public-paths
 * y protege los endpoints con @PreAuthorize("hasRole('ADMIN')") o similar.
 */
@AutoConfiguration(before = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class UrbanMoveCommonAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public JwtTokenService jwtTokenService(SecurityProperties properties) {
        return new JwtTokenService(properties.jwtSecret(), properties.jwtExpirationSeconds());
    }

    /** Evita que Spring Boot cree un usuario en memoria con contraseña aleatoria: aquí solo hay JWT. */
    @Bean
    public UserDetailsService noUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("No hay usuarios locales: la autenticación es por JWT");
        };
    }

    @Bean
    public SecurityFilterChain urbanMoveSecurityFilterChain(HttpSecurity http, JwtTokenService tokens,
            SecurityProperties properties, ObjectMapper mapper) throws Exception {
        List<String> open = new ArrayList<>(List.of("/actuator/health", "/actuator/health/**", "/actuator/info"));
        open.addAll(properties.publicPaths());

        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(open.toArray(new String[0])).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, mapper, HttpStatus.UNAUTHORIZED, "Token ausente o inválido"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, mapper, HttpStatus.FORBIDDEN,
                                        "No tienes permisos para esta operación")))
                .addFilterBefore(new JwtAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, HttpStatus status,
            String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(),
                new ErrorResponse(status.name(), message, CorrelationIdFilter.current()));
    }
}
