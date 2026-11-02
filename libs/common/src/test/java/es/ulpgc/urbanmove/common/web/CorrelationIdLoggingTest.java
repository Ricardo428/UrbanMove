package es.ulpgc.urbanmove.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Comprueba que logback-spring.xml imprime el correlationId del MDC en cada línea de log.
 * Arranca un Spring Boot vacío porque es Spring Boot (no Logback solo) quien carga logback-spring.xml.
 */
@SpringBootTest(classes = CorrelationIdLoggingTest.NoBeans.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdLoggingTest.class);

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void logsInsideARequestCarryItsCorrelationId(CapturedOutput output) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "c-prueba01");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> log.info("dentro de la petición"));

        assertThat(output).contains("[c-prueba01]").contains("dentro de la petición");
    }

    @Test
    void logsOutsideARequestSaySinId(CapturedOutput output) {
        log.info("fuera de una petición");

        assertThat(output).contains("[sin-id]").contains("fuera de una petición");
    }

    /** Contexto vacío: solo hace falta que Spring Boot arranque y configure los logs. */
    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }
}
