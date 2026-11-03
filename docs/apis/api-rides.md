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

**Orden de los pasos** (ver [adr-002-reservations.md](../adr-002-reservations.md)):
1. Si hay `reservationId`, Rides consulta la reserva en Reservations con su token de servicio y comprueba que es del usuario, que es de ese vehículo y que está `PENDING` o `CONFIRMED`. Si algo falla, responde con error sin guardar nada.
2. Guarda el viaje en `STARTING`.
3. Pide a Availability que ponga el vehículo `IN_USE`.
4. Si Availability acepta, el viaje pasa a `IN_PROGRESS` y se publica `RideStarted` en la misma transacción. Si responde 409 o 404, el viaje queda `REJECTED`. Si no responde, el viaje queda `STARTING` y se responde 503.

**Reintento tras un 503:** si el usuario vuelve a iniciar el viaje con el mismo vehículo y la misma reserva, Rides reutiliza el viaje `STARTING` y repite la llamada con el mismo `rideId`. Como `start` es idempotente por `rideId`, no se crea un segundo viaje.

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
| `409 Conflict` | El vehículo no está disponible o está reservado por otro usuario; la reserva es de otro vehículo; o la reserva ya no está activa. |
| `503 Service Unavailable` | Availability o Reservations no responden a tiempo. |

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

**Cálculos:** `durationMinutes` cuenta cada minuto empezado (mínimo 1). `preliminaryCost` = minutos × 0,15 € (configurable con `RIDE_COST_PER_MINUTE`). Hasta que exista telemetría, `batteryLevelPercent` (en `RideFinished`) = 100 − minutos × `BATTERY_DRAIN_PER_MINUTE` (1 por defecto), con un mínimo de 0.

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `destinationZoneId`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El viaje es de otro usuario. |
| `404 Not Found` | El viaje o la zona de destino no existen. |
| `409 Conflict` | El viaje ya estaba finalizado, o no está en curso (`STARTING` o `REJECTED`). |
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
`status` puede ser `STARTING` (esperando a Availability), `IN_PROGRESS`, `COMPLETED` o `REJECTED` (Availability no aceptó el vehículo).

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El viaje es de otro usuario. |
| `404 Not Found` | El viaje no existe. |

**Evento emitido:** ninguno.
