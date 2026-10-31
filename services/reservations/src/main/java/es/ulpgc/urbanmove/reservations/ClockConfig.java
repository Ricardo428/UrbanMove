package es.ulpgc.urbanmove.reservations;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj de los plazos de caducidad. Es un bean para que los tests puedan adelantar el tiempo. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
