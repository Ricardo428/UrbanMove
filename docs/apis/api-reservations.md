# Contrato de API REST: Servicio Reservations

Gestiona el ciclo de vida temporal de una reserva de un vehículo. Todas las peticiones llevan `Authorization: Bearer <token_jwt>`. El `userId` se extrae del token.

**Estados de una reserva:** `PENDING`, `CONFIRMED`, `CANCELLED`, `EXPIRED`, `REJECTED` y `USED` (el usuario inició el viaje con ella).

**Plazos (configurables):** una reserva `PENDING` caduca a los 5 minutos si no se confirma. Al confirmarla pasa a `CONFIRMED` y dispone de 15 minutos desde la confirmación para iniciar el viaje. La caducidad la ejecuta una Lambda con el endpoint de expiración (sección 5).

**Reserva atómica:** para no reservar dos veces el mismo vehículo, Reservations llama de forma síncrona a Availability. Ver [adr-002-reservations.md](../adr-002-reservations.md).

**Formato de error:**
```json
{
  "error": "CONFLICT",
  "message": "El vehículo no está disponible",
  "correlationId": "c-5d02aa31"
}
```

## 1. Crear Reserva
**Endpoint:** `POST /api/v1/reservations` (rol `USER`) \
**Descripción:** Intenta reservar un vehículo disponible. El servicio guarda la reserva como `PENDING`, pide a `Availability` que reserve el vehículo y, si lo consigue, publica el evento.

**Cuerpo de la petición (Request):**
```json
{
  "vehicleId": "v-0042"
}
```
**Respuesta Exitosa (201 Created):**
```json
{
  "reservationId": "res-7731",
  "vehicleId": "v-0042",
  "status": "PENDING",
  "expiresAt": "2026-10-13T10:20:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `vehicleId`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un usuario con rol `USER`. |
| `404 Not Found` | El vehículo no existe. |
| `409 Conflict` | El vehículo no está disponible. La reserva queda como `REJECTED`. |
| `503 Service Unavailable` | Availability no responde a tiempo. La reserva queda `PENDING` y caduca sola. |

**Evento emitido:** `VehicleReserved`.

## 2. Consultar Reserva
**Endpoint:** `GET /api/v1/reservations/{reservationId}` \
**Descripción:** Devuelve el estado de una reserva. Solo su propietario o un `ADMIN` pueden consultarla.

**Respuesta Exitosa (200 OK):**
```json
{
  "reservationId": "res-7731",
  "vehicleId": "v-0042",
  "userId": "u-1001",
  "status": "CONFIRMED",
  "createdAt": "2026-10-13T10:15:00Z",
  "expiresAt": "2026-10-13T10:31:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | La reserva es de otro usuario. |
| `404 Not Found` | La reserva no existe. |

**Evento emitido:** ninguno.

## 3. Confirmar Reserva
**Endpoint:** `POST /api/v1/reservations/{reservationId}/confirm` \
**Descripción:** El usuario confirma una reserva `PENDING`. Pasa a `CONFIRMED` y se amplía su plazo de caducidad.

**Respuesta Exitosa (200 OK):**
```json
{
  "reservationId": "res-7731",
  "status": "CONFIRMED",
  "expiresAt": "2026-10-13T10:31:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | La reserva es de otro usuario. |
| `404 Not Found` | La reserva no existe. |
| `409 Conflict` | La reserva ya no está `PENDING`: caducó, se canceló o ya estaba confirmada. |

**Evento emitido:** ninguno.

## 4. Cancelar Reserva
**Endpoint:** `POST /api/v1/reservations/{reservationId}/cancel` \
**Descripción:** El usuario cancela voluntariamente su reserva antes de que expire.

**Respuesta Exitosa (200 OK):**

```json
{
  "reservationId": "res-7731",
  "status": "CANCELLED",
  "message": "Reserva cancelada. El vehículo vuelve a estar disponible."
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | La reserva es de otro usuario. |
| `404 Not Found` | La reserva no existe. |
| `409 Conflict` | La reserva ya no se puede cancelar: está `EXPIRED`, `USED` o `CANCELLED`. |

**Evento emitido:** `ReservationCancelled`.

## 5. Expirar Reserva (Uso interno de la Lambda de expiración)
**Endpoint:** `POST /api/v1/reservations/{reservationId}/expire` (rol `ADMIN`, cuenta de servicio de la Lambda) \
**Descripción:** Marca como `EXPIRED` una reserva `PENDING` o `CONFIRMED` cuyo plazo ha vencido. La Lambda lo invoca periódicamente para cada reserva vencida. Es idempotente: si la reserva ya estaba `EXPIRED`, responde `200` sin volver a publicar el evento.

**Respuesta Exitosa (200 OK):**
```json
{
  "reservationId": "res-7731",
  "status": "EXPIRED",
  "expiredAt": "2026-10-13T10:20:05Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un `ADMIN`. |
| `404 Not Found` | La reserva no existe. |
| `409 Conflict` | La reserva no ha vencido todavía, o ya está `USED` o `CANCELLED`. |

**Evento emitido:** `ReservationExpired`.

## Eventos que consume

| Evento | Efecto |
| :--- | :--- |
| `RideStarted` | Si lleva `reservationId`, marca esa reserva como `USED` para que la Lambda no la expire mientras dura el viaje. |
