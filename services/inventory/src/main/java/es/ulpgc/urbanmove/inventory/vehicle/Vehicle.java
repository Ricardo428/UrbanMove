package es.ulpgc.urbanmove.inventory.vehicle;

import es.ulpgc.urbanmove.events.VehicleType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Un recurso del catálogo. El id público es v-0042; en la base de datos es el número. */
public record Vehicle(
        long id,
        VehicleType vehicleType,
        String serialNumber,
        String model,
        LocalDate purchaseDate,
        AdministrativeStatus administrativeStatus) {

    private static final Pattern PUBLIC_ID = Pattern.compile("^v-(\\d{1,15})$");

    public String publicId() {
        return publicIdOf(id);
    }

    public static String publicIdOf(long id) {
        return String.format("v-%04d", id);
    }

    /** Convierte v-0042 en 42. Un id con otro formato devuelve vacío. */
    public static Optional<Long> parsePublicId(String publicId) {
        Matcher matcher = PUBLIC_ID.matcher(publicId);
        return matcher.matches() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }
}
