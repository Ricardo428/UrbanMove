package es.ulpgc.urbanmove.maintenance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Órdenes de mantenimiento: las crea al consumir VehicleBatteryLow y las ejecuta un operario desde la App de
 * Mantenimiento. El estado de los recursos lo cambia availability (docs/apis/api-maintenance-orders.md).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MaintenanceOrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaintenanceOrdersApplication.class, args);
    }
}
