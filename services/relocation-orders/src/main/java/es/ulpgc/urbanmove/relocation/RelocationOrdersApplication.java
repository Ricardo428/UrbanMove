package es.ulpgc.urbanmove.relocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Órdenes de recolocación: las crea al consumir DemandHotspotDetected y las ejecuta un operario desde la App de
 * Mantenimiento. El estado de los recursos lo cambia availability (docs/apis/api-relocation-orders.md).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RelocationOrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelocationOrdersApplication.class, args);
    }
}
