package es.ulpgc.urbanmove.common.client;

import es.ulpgc.urbanmove.common.security.JwtTokenService;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Construye el RestClient con el que un servicio llama a otro de forma síncrona. Todas esas llamadas comparten:
 * <ul>
 *   <li>un timeout de conexión y de lectura, para que un servicio lento o caído no cuelgue la petición del usuario
 *       (se recibe una ResourceAccessException);</li>
 *   <li>el token de servicio y el X-Correlation-Id en cada petición ({@link ServiceCallHeaders}).</li>
 * </ul>
 * Cada cliente solo añade lo suyo: la ruta, el cuerpo y cómo traducir las respuestas.
 */
public final class ServiceRestClients {

    private ServiceRestClients() {
    }

    public static RestClient create(RestClient.Builder builder, String baseUrl, Duration timeout, String serviceId,
            JwtTokenService tokens) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(new ServiceCallHeaders(serviceId, tokens))
                .build();
    }
}
