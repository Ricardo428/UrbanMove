# Contratos de Eventos

## Sobre Común (Esquema base para todos los eventos)
Todo evento que viaje por el bus (SNS/SQS) tendrá esta estructura básica:
*   `eventId`: UUID único del evento.
*   `timestamp`: Fecha y hora de emisión (ISO 8601).
*   `source`: Microservicio que origina el evento (ej. `identity-service`).
*   `type`: Nombre del evento (ej. `UserRegistered`).
*   `version`: Versión del esquema (ej. `v1`).
*   `correlationId`: Identificador para trazar el flujo completo entre servicios.
*   `payload`: Datos propios de cada evento (ver los ejemplos más abajo).

## Lista de Eventos

| Evento | Productor (Quién lo emite) | Consumidor (Quién lo escucha) | Descripción |
| :--- | :--- | :--- | :--- |
| `UserRegistered` | Identity | Data Lake | Alta de usuario. El perfil se completa después en Profiles. |
| `VehiclePurchased` | Inventory | Disponibilidad, Data Lake | Incorporación de nueva bici o patinete. |
| `VehicleReserved` | Reservas | Historial, Data Lake | Reserva creada sobre un recurso. |
| `ReservationCancelled` | Reservas | Disponibilidad, Historial, Data Lake | Reserva cancelada por el usuario. |
| `ReservationExpired` | Reservas (a petición de la Lambda Expiración) | Disponibilidad, Historial, Data Lake | Reserva liberada por timeout. |
| `RideStarted` | Viajes | Reservas, Historial, Data Lake | Inicio de uso de un recurso. Reservas marca como usada la reserva asociada. |
| `RideFinished` | Viajes | Disponibilidad, Historial, Data Lake | Finalización del viaje. |
| `VehicleBatteryLow` | Lambda Detección Mantenimiento | Órdenes Mantenimiento, Historial, Data Lake | Detección de batería baja. |
| `VehicleWithdrawnForMaintenance` | Órdenes Mantenimiento | Historial, Data Lake | Recurso retirado de la vía pública. |
| `VehicleReturnedToService` | Órdenes Mantenimiento | Historial, Data Lake | Recurso vuelve a estar disponible. |
| `DemandHotspotDetected` | Lambda Recolocación | Órdenes Recolocación, Data Lake | Zona con más demanda que recursos. |
| `RelocationOrderCreated` | Órdenes Recolocación | Historial, Data Lake | Orden para mover recursos entre zonas. La App de Mantenimiento la consulta por API. |
| `RelocationOrderCompleted` | Órdenes Recolocación | Historial, Data Lake | Recolocación finalizada. |

Las transiciones de estado del recurso que no pasan por un evento (reservar, iniciar viaje, retirada, recarga, reparación, recolocación) las solicita el servicio correspondiente a Availability por API. Ver [states.md](states.md) y [adr-002-reservations.md](adr-002-reservations.md).

## Reglas para productores y consumidores

*   **Idempotencia:** el bus entrega al menos una vez, así que un evento puede llegar duplicado. Cada consumidor guarda los `eventId` que ya procesó y descarta los repetidos.
*   **Orden:** no está garantizado. Un consumidor no debe depender de recibir los eventos en el orden en que se emitieron; usa `timestamp` y el estado actual.
*   **Persistencia antes de publicar:** el productor guarda el evento en su propia base de datos (tabla outbox) en la misma transacción que el cambio de estado, y un proceso aparte lo publica.
*   **Fallos:** tras 3 intentos fallidos el mensaje pasa a la dead-letter queue de su cola.
*   **Batería simulada:** hasta que exista un servicio de telemetría, `rides` calcula `batteryLevelPercent` al finalizar el viaje y lo incluye en `RideFinished`.

## Ejemplos de payload

Los ejemplos muestran el evento completo. Todos los identificadores son de ejemplo.

### UserRegistered
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b01",
  "timestamp": "2026-10-13T09:00:00Z",
  "source": "identity-service",
  "type": "UserRegistered",
  "version": "v1",
  "correlationId": "c-8a41f0d2",
  "payload": {
    "userId": "u-1001",
    "role": "USER",
    "registeredAt": "2026-10-13T09:00:00Z"
  }
}
```

### VehiclePurchased
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b02",
  "timestamp": "2026-10-13T09:05:00Z",
  "source": "inventory-service",
  "type": "VehiclePurchased",
  "version": "v1",
  "correlationId": "c-1b77e9a0",
  "payload": {
    "vehicleId": "v-0042",
    "vehicleType": "SCOOTER",
    "model": "Xiaomi Pro 4",
    "purchaseDate": "2026-10-13",
    "zoneId": "zone-centro"
  }
}
```

### VehicleReserved
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b03",
  "timestamp": "2026-10-13T10:15:00Z",
  "source": "reservations-service",
  "type": "VehicleReserved",
  "version": "v1",
  "correlationId": "c-5d02aa31",
  "payload": {
    "reservationId": "res-7731",
    "vehicleId": "v-0042",
    "userId": "u-1001",
    "zoneId": "zone-centro",
    "expiresAt": "2026-10-13T10:20:00Z"
  }
}
```

### ReservationCancelled
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b04",
  "timestamp": "2026-10-13T10:17:00Z",
  "source": "reservations-service",
  "type": "ReservationCancelled",
  "version": "v1",
  "correlationId": "c-5d02aa31",
  "payload": {
    "reservationId": "res-7731",
    "vehicleId": "v-0042",
    "userId": "u-1001",
    "cancelledAt": "2026-10-13T10:17:00Z"
  }
}
```

### ReservationExpired
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b05",
  "timestamp": "2026-10-13T10:20:05Z",
  "source": "reservations-service",
  "type": "ReservationExpired",
  "version": "v1",
  "correlationId": "c-5d02aa31",
  "payload": {
    "reservationId": "res-7731",
    "vehicleId": "v-0042",
    "userId": "u-1001",
    "expiredAt": "2026-10-13T10:20:05Z"
  }
}
```

### RideStarted
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b06",
  "timestamp": "2026-10-13T10:30:00Z",
  "source": "rides-service",
  "type": "RideStarted",
  "version": "v1",
  "correlationId": "c-9e3340b7",
  "payload": {
    "rideId": "ride-5520",
    "vehicleId": "v-0042",
    "userId": "u-1001",
    "reservationId": "res-7731",
    "originZoneId": "zone-centro",
    "startTime": "2026-10-13T10:30:00Z"
  }
}
```
`reservationId` es `null` si el usuario inicia el viaje sin reserva previa.

### RideFinished
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b07",
  "timestamp": "2026-10-13T11:00:00Z",
  "source": "rides-service",
  "type": "RideFinished",
  "version": "v1",
  "correlationId": "c-9e3340b7",
  "payload": {
    "rideId": "ride-5520",
    "vehicleId": "v-0042",
    "userId": "u-1001",
    "originZoneId": "zone-centro",
    "destinationZoneId": "zone-norte",
    "endTime": "2026-10-13T11:00:00Z",
    "durationMinutes": 30,
    "preliminaryCost": 4.5,
    "batteryLevelPercent": 14
  }
}
```

### VehicleBatteryLow
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b08",
  "timestamp": "2026-10-13T11:00:02Z",
  "source": "lambda-maintenance-detection",
  "type": "VehicleBatteryLow",
  "version": "v1",
  "correlationId": "c-9e3340b7",
  "payload": {
    "vehicleId": "v-0042",
    "reason": "BATTERY_LOW",
    "batteryLevelPercent": 14,
    "detectedAt": "2026-10-13T11:00:02Z"
  }
}
```
`reason` admite `BATTERY_LOW`, `FAULT` y `USAGE` (demasiados usos desde la última revisión).

### VehicleWithdrawnForMaintenance
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b09",
  "timestamp": "2026-10-13T12:00:00Z",
  "source": "maintenance-orders-service",
  "type": "VehicleWithdrawnForMaintenance",
  "version": "v1",
  "correlationId": "c-9e3340b7",
  "payload": {
    "vehicleId": "v-0042",
    "orderId": "mo-310",
    "reason": "BATTERY_LOW",
    "withdrawnAt": "2026-10-13T12:00:00Z"
  }
}
```

### VehicleReturnedToService
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b10",
  "timestamp": "2026-10-13T15:00:00Z",
  "source": "maintenance-orders-service",
  "type": "VehicleReturnedToService",
  "version": "v1",
  "correlationId": "c-9e3340b7",
  "payload": {
    "vehicleId": "v-0042",
    "orderId": "mo-310",
    "zoneId": "zone-norte",
    "returnedAt": "2026-10-13T15:00:00Z"
  }
}
```

### DemandHotspotDetected
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b11",
  "timestamp": "2026-10-13T16:00:00Z",
  "source": "lambda-relocation",
  "type": "DemandHotspotDetected",
  "version": "v1",
  "correlationId": "c-2f6b7c18",
  "payload": {
    "zoneId": "zone-centro",
    "vehicleType": "SCOOTER",
    "availableCount": 2,
    "requestsLastHour": 14,
    "windowStart": "2026-10-13T15:00:00Z",
    "windowEnd": "2026-10-13T16:00:00Z"
  }
}
```

### RelocationOrderCreated
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b12",
  "timestamp": "2026-10-13T16:00:03Z",
  "source": "relocation-orders-service",
  "type": "RelocationOrderCreated",
  "version": "v1",
  "correlationId": "c-2f6b7c18",
  "payload": {
    "orderId": "ro-210",
    "fromZoneId": "zone-norte",
    "toZoneId": "zone-centro",
    "vehicleType": "SCOOTER",
    "quantity": 4,
    "createdAt": "2026-10-13T16:00:03Z"
  }
}
```

### RelocationOrderCompleted
```json
{
  "eventId": "3f1c9a52-7b0e-4c1d-9a55-0e6d2f7a1b13",
  "timestamp": "2026-10-13T17:30:00Z",
  "source": "relocation-orders-service",
  "type": "RelocationOrderCompleted",
  "version": "v1",
  "correlationId": "c-2f6b7c18",
  "payload": {
    "orderId": "ro-210",
    "vehicleIds": ["v-0051", "v-0052", "v-0053", "v-0054"],
    "toZoneId": "zone-centro",
    "completedAt": "2026-10-13T17:30:00Z"
  }
}
```
