package es.ulpgc.urbanmove.history;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Historial de reservas, viajes y operaciones. Solo consume eventos (docs/apis/api-history.md). */
@SpringBootApplication
public class HistoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(HistoryApplication.class, args);
    }
}
