package es.ulpgc.urbanmove.lambdas.ingestion;

import es.ulpgc.urbanmove.events.EventEnvelope;
import java.time.ZoneOffset;

/**
 * Dónde se guarda cada evento en la capa raw del Data Lake:
 * {@code raw/event_date=2026-11-14/event_type=VehicleReserved/source=reservations-service/<eventId>.json}.
 *
 * <ul>
 *   <li>La fecha es la del evento (su {@code timestamp}), no la del momento de ingesta: un evento que se
 *       reentrega o llega tarde cae en la misma partición.</li>
 *   <li>El nombre del fichero es el {@code eventId}: una entrega duplicada escribe la misma clave y no crea
 *       un segundo fichero (la ingesta es idempotente).</li>
 *   <li>Las particiones siguen el formato {@code clave=valor} para que Athena, Glue o Spark las reconozcan solos.</li>
 * </ul>
 */
final class RawKeys {

    static final String PREFIX = "raw/";

    private RawKeys() {
    }

    static String keyFor(EventEnvelope event) {
        return PREFIX
                + "event_date=" + event.timestamp().atZone(ZoneOffset.UTC).toLocalDate() + "/"
                + "event_type=" + safe(event.type()) + "/"
                + "source=" + safe(event.source()) + "/"
                + event.eventId() + ".json";
    }

    /** Los valores salen del propio evento: se limpian para que no puedan alterar la ruta (por ejemplo con "/"). */
    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
