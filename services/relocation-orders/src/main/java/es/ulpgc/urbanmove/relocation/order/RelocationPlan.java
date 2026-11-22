package es.ulpgc.urbanmove.relocation.order;

import es.ulpgc.urbanmove.relocation.availability.CountOutcome;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * De dónde traer recursos a una zona con déficit y cuántos (docs/apis/api-relocation-orders.md, "Cómo nace una
 * orden"). Es un cálculo puro: no lee la base de datos ni llama a nadie, así que se prueba sin Spring.
 *
 * @param fromZoneId zona origen: la distinta del destino con más recursos disponibles
 * @param quantity   recursos a mover, siempre mayor que 0
 */
public record RelocationPlan(String fromZoneId, int quantity) {

    /** En caso de empate, la zona de id menor, para que el resultado no dependa del orden de la respuesta. */
    private static final Comparator<Map.Entry<String, Integer>> MOST_AVAILABLE_FIRST =
            Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey());

    /**
     * quantity = min(lo que falta en el destino para llegar a targetAvailable,
     *                lo que el origen puede ceder sin bajar de minLeftInOrigin).
     *
     * @return vacío si no hace falta orden: el destino ya llega al objetivo, no hay otra zona o ninguna puede ceder
     */
    public static Optional<RelocationPlan> decide(String toZoneId, CountOutcome.Counted counts, int targetAvailable,
            int minLeftInOrigin) {
        int missingInDestination = targetAvailable - counts.in(toZoneId);
        return counts.availableByZone().entrySet().stream()
                .filter(zone -> !zone.getKey().equals(toZoneId))
                .sorted(MOST_AVAILABLE_FIRST)
                .findFirst()
                .map(origin -> new RelocationPlan(origin.getKey(),
                        Math.min(missingInDestination, origin.getValue() - minLeftInOrigin)))
                .filter(plan -> plan.quantity() > 0);
    }
}
