# Contrato de API REST: Servicio Rides

Gestiona el uso efectivo de los recursos. Todas las peticiones llevan `Authorization: Bearer <token_jwt>`. El `userId` se extrae del token.

**Formato de error:**
```json
{
  "error": "CONFLICT",
  "message": "El vehículo no está disponible",
  "correlationId": "c-9e3340b7"
}
```

## 1. Iniciar Viaje
**Endpoint:** `POST /api/v1/rides/start` (rol `USER`) \
**Descripción:** Inicia el uso de un recurso, ya sea desde una reserva previa o directamente escaneando un vehículo disponible. Rides pide a `Availability` que pase el vehículo a `IN_USE` y obtiene de ella la zona de origen. Ver [adr-002-reservations.md](../adr-002-reservations.md).

**Cuerpo de la petición (Request):**
```json
{
  "vehicleId": "v-0042",
  "reservationId": "res-7731"
}
```
`reservationId` es opcional: se omite si el usuario inicia el viaje sin reserva.

**Respuesta Exitosa (201 Created):**

```json
{
  "rideId": "ride-5520",
  "vehicleId": "v-0042",
  "originZoneId": "zone-centro",
  "startTime": "2026-10-13T10:30:00Z",
  "status": "IN_PROGRESS"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `vehicleId`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un `USER`, o la reserva pertenece a otro usuario. |
| `404 Not Found` | El vehículo o la reserva no existen. |
| `409 Conflict` | El vehículo no está disponible, o está reservado por otro usuario. |
| `503 Service Unavailable` | Availability no responde a tiempo. |

**Evento emitido:** `RideStarted`.

## 2. Finalizar Viaje
**Endpoint:** `POST /api/v1/rides/{rideId}/finish` \
**Descripción:** Termina el trayecto. El usuario debe indicar la zona de destino para comprobar si es un área permitida con el servicio `Zones`. Si Zones no responde, el viaje se finaliza igualmente y la respuesta lleva `zoneValidated: false`, para no dejar al usuario con el viaje abierto.

**Cuerpo de la petición (Request):**

```json
{
  "destinationZoneId": "zone-norte"
}
```

**Respuesta Exitosa (200 OK):**

```json
{
  "rideId": "ride-5520",
  "endTime": "2026-10-13T11:00:00Z",
  "durationMinutes": 30,
  "originZoneId": "zone-centro",
  "destinationZoneId": "zone-norte",
  "zoneValidated": true,
  "preliminaryCost": 4.50,
  "status": "COMPLETED"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `destinationZoneId`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El viaje es de otro usuario. |
| `404 Not Found` | El viaje o la zona de destino no existen. |
| `409 Conflict` | El viaje ya estaba finalizado. |
| `422 Unprocessable Entity` | La zona de destino existe pero no permite aparcar. |

**Evento emitido:** `RideFinished`.

## 3. Consultar Viaje
**Endpoint:** `GET /api/v1/rides/{rideId}` \
**Descripción:** Devuelve el estado de un viaje. Solo su propietario o un `ADMIN` pueden consultarlo.

**Respuesta Exitosa (200 OK):**
```json
{
  "rideId": "ride-5520",
  "vehicleId": "v-0042",
  "userId": "u-1001",
  "originZoneId": "zone-centro",
  "startTime": "2026-10-13T10:30:00Z",
  "status": "IN_PROGRESS"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El viaje es de otro usuario. |
| `404 Not Found` | El viaje no existe. |

**Evento emitido:** ninguno.
