
Registro inmutable de lo que ocurre en el sistema: reservas, viajes, mantenimiento y recolocación. Solo **lee eventos**: no llama a ningún servicio y ningún servicio le llama a él; lo consultan las apps. Guarda cada evento como una entrada y nunca la modifica ni la borra. Todas las peticiones llevan `Authorization: Bearer <token_jwt>`.

**Base de datos:** `history_db`, tabla `history_entries` (una fila por evento, clave `event_id`).

**Orden:** el bus no garantiza el orden de llegada (ver [events.md](../events.md)), así que las entradas se ordenan por el `timestamp` del evento (cuándo ocurrió), no por cuándo llegó a History. La más reciente primero.

**Formato de error:**
```json
{
  "error": "FORBIDDEN",
  "message": "Solo MAINTENANCE o ADMIN pueden consultar el historial de un vehículo",
  "correlationId": "c-5d02aa31"
}
```

## 1. Mi historial
**Endpoint:** `GET /api/v1/history/me?limit=50` (rol `USER`) \
**Descripción:** Devuelve las reservas y viajes del usuario del token. El `userId` sale del token, nunca de la URL, así que nadie puede pedir el historial de otro. `limit` es opcional (por defecto 50, máximo 200).

**Respuesta Exitosa (200 OK):**
```json
{
  "entries": [
    {
      "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b07",
      "type": "RideFinished",
      "occurredAt": "2026-10-13T11:00:00Z",
      "vehicleId": "v-0042",
      "reservationId": null,
      "rideId": "ride-5520",
      "details": { "originZoneId": "zone-centro", "destinationZoneId": "zone-norte", "durationMinutes": 30, "preliminaryCost": 4.5 }
    },
    {
      "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b03",
      "type": "VehicleReserved",
      "occurredAt": "2026-10-13T10:15:00Z",
      "vehicleId": "v-0042",
      "reservationId": "res-7731",
      "rideId": null,
      "details": { "zoneId": "zone-centro", "expiresAt": "2026-10-13T10:20:00Z" }
    }
  ]
}
```
`details` es el payload del evento tal como llegó, sin los campos que ya van fuera. Un usuario sin actividad recibe `"entries": []`.

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `limit` no es un número entre 1 y 200. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un usuario con rol `USER`. |

**Evento emitido:** ninguno.

## 2. Historial de un vehículo
**Endpoint:** `GET /api/v1/history/vehicles/{vehicleId}?limit=50` (rol `MAINTENANCE` o `ADMIN`) \
**Descripción:** Devuelve todo lo ocurrido con un vehículo: reservas, viajes, retiradas y vueltas al servicio. Misma forma de respuesta que la sección 1, con `userId` en cada entrada.

History no pregunta a Inventory si el vehículo existe (no llama a nadie): un `vehicleId` desconocido devuelve `"entries": []`, no `404`.

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `limit` no es un número entre 1 y 200. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token es de un `USER`. |

**Evento emitido:** ninguno.

## Eventos que consume

Cola `urbanmove-history`. Cada evento se guarda una sola vez: `libs/messaging` apunta su `eventId` en `processed_events` en la misma transacción que el `INSERT` de la entrada (ver [ADR-005](../adr-005-outbox-consumidores.md)). Si el `INSERT` falla, se deshacen los dos y SQS lo reentrega; tras 3 intentos, a `urbanmove-history-dlq`.

| Evento | Entrada que guarda | Estado |
| :--- | :--- | :--- |
| `VehicleReserved` | Reserva creada (usuario y vehículo) | Implementado |
| `ReservationCancelled` | Reserva cancelada | Implementado |
| `ReservationExpired` | Reserva caducada | Implementado |
| `RideStarted` | Viaje iniciado (con su `reservationId` si lo hay) | Implementado |
| `RideFinished` | Viaje finalizado (zonas, duración y coste) | Implementado |
| `VehicleBatteryLow` | Batería baja detectada (vehículo) | Cuando exista su productor |
| `VehicleWithdrawnForMaintenance` | Retirada por mantenimiento (vehículo) | Cuando exista su productor |
| `VehicleReturnedToService` | Vuelta al servicio (vehículo) | Cuando exista su productor |
| `RelocationOrderCreated` | Orden de recolocación (sin vehículo) | Cuando exista su productor |
| `RelocationOrderCompleted` | Una entrada por cada vehículo recolocado | Cuando exista su productor |

Solo llega a History lo que se publica como evento. Zones, Availability y Analytics no publican, y `UserRegistered`, `UserDeactivated` y `VehiclePurchased` se publican pero History no los consume (ver [events.md](../events.md)).

**Límites conocidos:**
- SQS guarda los mensajes 4 días ([ADR-005](../adr-005-outbox-consumidores.md)): los eventos anteriores al primer arranque de History no aparecerán.
- Si History cae, nadie más se entera: los eventos esperan en su cola y la app solo pierde la consulta del historial (degradación funcional).
