package es.ulpgc.urbanmove.rides;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj de las horas de inicio y fin de los viajes. Es un bean para que los tests puedan fijar el tiempo. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
