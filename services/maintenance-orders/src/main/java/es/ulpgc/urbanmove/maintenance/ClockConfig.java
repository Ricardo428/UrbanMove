package es.ulpgc.urbanmove.maintenance;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj de las marcas de tiempo de las órdenes. Es un bean para que los tests puedan fijarlo. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
