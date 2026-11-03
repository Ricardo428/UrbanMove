package es.ulpgc.urbanmove.rides.zones;

/** Lo que puede pasar al consultar una zona en zones. */
public sealed interface ZoneLookup {

    record Found(ZoneSnapshot zone) implements ZoneLookup {
    }

    /** Zones no conoce la zona (404). */
    record NotFound() implements ZoneLookup {
    }

    /** Zones no respondió a tiempo o falló: el viaje se finaliza sin validar la zona. */
    record Unreachable() implements ZoneLookup {
    }
}
