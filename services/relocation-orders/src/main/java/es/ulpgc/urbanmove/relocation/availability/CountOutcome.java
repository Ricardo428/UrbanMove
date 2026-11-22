package es.ulpgc.urbanmove.relocation.availability;

import java.util.Map;

/** Lo que puede pasar al pedir a availability cuántos recursos disponibles hay de un tipo en cada zona. */
public sealed interface CountOutcome {

    /** Recursos AVAILABLE por zona. Una zona sin ninguno no aparece en el mapa. */
    record Counted(Map<String, Integer> availableByZone) implements CountOutcome {

        public Counted {
            availableByZone = Map.copyOf(availableByZone);
        }

        public int in(String zoneId) {
            return availableByZone.getOrDefault(zoneId, 0);
        }
    }

    /** Availability no respondió a tiempo o falló: no se sabe cuántos hay. */
    record Unreachable() implements CountOutcome {
    }
}
