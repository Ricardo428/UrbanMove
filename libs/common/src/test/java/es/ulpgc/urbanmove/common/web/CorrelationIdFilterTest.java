package es.ulpgc.urbanmove.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesAValidIncomingId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "c-8a41f0d2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInside = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInside.set(CorrelationIdFilter.current()));

        assertThat(seenInside.get()).isEqualTo("c-8a41f0d2");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("c-8a41f0d2");
        assertThat(CorrelationIdFilter.current()).isNull();
    }

    @Test
    void generatesAnIdWhenMissing() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("c-[0-9a-f]{8}");
    }

    @Test
    void replacesAnInvalidId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "mal valor con espacios\t");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches("c-[0-9a-f]{8}");
    }
}
