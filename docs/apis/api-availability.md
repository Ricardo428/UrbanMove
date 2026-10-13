# Contrato de API REST: Servicio Availability

Mantiene qué recursos pueden reservarse en cada momento, su estado operativo y la zona en la que están, sin mezclar inventario, reservas ni mantenimiento. **Es la única dueña del estado del recurso y valida toda transición** según [states.md](../states.md). Los endpoints marcados como internos los llaman otros servicios, no las apps.

Todas las peticiones llevan `Authorization: Bearer <token_jwt>`.

**Formato de error:**
```json
{
  "error": "CONFLICT",
  "message": "El vehículo v-0042 no está en estado AVAILABLE",
  "correlationId": "c-5d02aa31"
}
```

## 1. Listar Recursos Disponibles
**Endpoint:** `GET /api/v1/availability?zoneId=zone-centro&type=SCOOTER` \
**Descripción:** Devuelve los recursos en estado `AVAILABLE`. Los dos filtros son opcionales. La app de usuarios lo usa para ver bicicletas y patinetes por zona.

**Respuesta Exitosa (200 OK):**
```json
[
  {
    "vehicleId": "v-0042",
    "vehicleType": "SCOOTER",
    "zoneId": "zone-centro",
    "status": "AVAILABLE"
  }
]
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `type` no es `BIKE` ni `SCOOTER`. |
| `401 Unauthorized` | Falta el token o no es válido. |

**Evento emitido:** ninguno.

## 2. Consultar Estado de un Recurso
**Endpoint:** `GET /api/v1/availability/{vehicleId}` \
**Descripción:** Devuelve el estado y la zona actual de un recurso.

**Respuesta Exitosa (200 OK):**
```json
{
  "vehicleId": "v-0042",
  "vehicleType": "SCOOTER",
  "zoneId": "zone-centro",
  "status": "RESERVED",
  "reservationId": "res-7731"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `404 Not Found` | El recurso no existe. |

**Evento emitido:** ninguno.

## 3. Reservar un Recurso (Uso interno de Reservations)
**Endpoint:** `POST /api/v1/availability/{vehicleId}/reserve` \
**Descripción:** Pasa el recurso de `AVAILABLE` a `RESERVED` con una sentencia condicional atómica. Si dos peticiones llegan a la vez, solo una gana. Es idempotente por `reservationId`. Ver [adr-002-reservations.md](../adr-002-reservations.md).

**Cuerpo de la petición (Request):**
```json
{
  "reservationId": "res-7731"
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "vehicleId": "v-0042",
  "status": "RESERVED",
  "reservationId": "res-7731",
  "zoneId": "zone-centro"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `reservationId`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un servicio autorizado (`ADMIN`). |
| `404 Not Found` | El recurso no existe. |
| `409 Conflict` | El recurso no está `AVAILABLE` y la reserva no es la que ya lo tiene. |

**Evento emitido:** ninguno. Lo publica Reservations.

## 4. Iniciar el Uso de un Recurso (Uso interno de Rides)
**Endpoint:** `POST /api/v1/availability/{vehicleId}/start` \
**Descripción:** Pasa el recurso a `IN_USE`, desde `AVAILABLE` (viaje sin reserva) o desde `RESERVED` (viaje con la reserva indicada). Devuelve la zona actual, que Rides guarda como origen del viaje.

**Cuerpo de la petición (Request):**
```json
{
  "rideId": "ride-5520",
  "reservationId": "res-7731"
}
```
`reservationId` es opcional.

**Respuesta Exitosa (200 OK):**
```json
{
  "vehicleId": "v-0042",
  "status": "IN_USE",
  "zoneId": "zone-centro"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `rideId`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un servicio autorizado (`ADMIN`). |
| `404 Not Found` | El recurso no existe. |
| `409 Conflict` | El recurso no está `AVAILABLE`, o está `RESERVED` por otra reserva. |

**Evento emitido:** ninguno. Lo publica Rides.

## 5. Cambiar el Estado de un Recurso (Uso interno de Órdenes de Mantenimiento y Recolocación)
**Endpoint:** `PUT /api/v1/availability/{vehicleId}/state` \
**Descripción:** Solicita una transición de estado que no sea reservar ni iniciar viaje: retirada, recarga, reparación, vuelta al servicio, recolocación o baja. Availability la valida contra [states.md](../states.md). Si cambia la zona del recurso (recolocación), se indica `zoneId`.

**Cuerpo de la petición (Request):**
```json
{
  "state": "WITHDRAWN",
  "reason": "Orden de mantenimiento mo-310"
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "vehicleId": "v-0042",
  "status": "WITHDRAWN",
  "zoneId": "zone-norte"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `state` no es uno de los nueve estados definidos. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |
| `404 Not Found` | El recurso no existe. |
| `409 Conflict` | La transición no está permitida desde el estado actual (por ejemplo, `RETIRED` desde `IN_USE`). |

**Evento emitido:** ninguno.

## Eventos que consume

| Evento | Efecto |
| :--- | :--- |
| `VehiclePurchased` | Crea el recurso en estado `AVAILABLE` con su zona inicial. |
| `ReservationCancelled` | Pasa el recurso de `RESERVED` a `AVAILABLE`, solo si el `reservationId` coincide. |
| `ReservationExpired` | Igual que la cancelación. |
| `RideFinished` | Pasa el recurso de `IN_USE` a `AVAILABLE`, o a `LOW_BATTERY` si `batteryLevelPercent` está por debajo del umbral. Actualiza la zona con `destinationZoneId`. |

El procesamiento es idempotente: se descartan los eventos cuyo `eventId` ya se procesó.
