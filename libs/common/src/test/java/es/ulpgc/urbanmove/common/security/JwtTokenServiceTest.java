package es.ulpgc.urbanmove.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    // Clave solo para tests, no es ninguna credencial real.
    private static final String SECRET = "clave-solo-para-tests-de-urbanmove-0123456789";
    private static final String OTHER_SECRET = "otra-clave-distinta-para-tests-urbanmove-9876";

    @Test
    void generatedTokenCanBeParsed() {
        JwtTokenService service = new JwtTokenService(SECRET, 3600);

        String token = service.generate("u-1001", Role.MAINTENANCE);
        AuthenticatedUser user = service.parse(token);

        assertThat(user.userId()).isEqualTo("u-1001");
        assertThat(user.role()).isEqualTo(Role.MAINTENANCE);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = new JwtTokenService(OTHER_SECRET, 3600).generate("u-1", Role.USER);

        assertThatThrownBy(() -> new JwtTokenService(SECRET, 3600).parse(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        JwtTokenService service = new JwtTokenService(SECRET, -60);

        String token = service.generate("u-1", Role.USER);

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtTokenService service = new JwtTokenService(SECRET, 3600);
        String token = service.generate("u-1", Role.USER);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void garbageIsRejected() {
        JwtTokenService service = new JwtTokenService(SECRET, 3600);

        assertThatThrownBy(() -> service.parse("esto-no-es-un-jwt")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void missingOrShortSecretFailsAtStartup() {
        assertThatThrownBy(() -> new JwtTokenService("", 3600)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenService(null, 3600)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtTokenService("corta", 3600)).isInstanceOf(IllegalStateException.class);
    }
}
