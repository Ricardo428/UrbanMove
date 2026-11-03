package es.ulpgc.urbanmove.rides.zones;

/** Lo que rides necesita saber de una zona para terminar un viaje en ella. La dueña de la zona es zones. */
public record ZoneSnapshot(String zoneId, boolean parkingAllowed, boolean active) {

    /** Solo se puede dejar un vehículo en una zona activa que permite aparcar. */
    public boolean allowsParking() {
        return active && parkingAllowed;
    }
}
