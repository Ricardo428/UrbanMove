# Contratos de Eventos

## Sobre Común (Esquema base para todos los eventos)
Todo evento que viaje por el bus (SNS/SQS) tendrá esta estructura básica:
*   `eventId`: UUID único del evento.
*   `timestamp`: Fecha y hora de emisión (ISO 8601).
*   `source`: Microservicio que origina el evento (ej. `identity-service`).
*   `type`: Nombre del evento (ej. `UserRegistered`).
*   `version`: Versión del esquema (ej. `v1`).
*   `correlationId`: Identificador para trazar el flujo completo entre servicios.

## Lista de Eventos

| Evento | Productor (Quién lo emite) | Consumidor (Quién lo escucha) | Descripción |
| :--- | :--- | :--- | :--- |
| `UserRegistered` | Identity / Profiles | Data Lake, Notificaciones (ampliación) | Alta de usuario y creación de perfil. |
| `VehiclePurchased` | Adquisiciones / Inventario | Data Lake, Disponibilidad | Incorporación de nueva bici o patinete. |
| `VehicleReserved` | Reservas | Disponibilidad, Historial, Data Lake | Reserva creada sobre un recurso. |
| `ReservationCancelled` | Reservas | Disponibilidad, Historial, Data Lake | Reserva cancelada por el usuario. |
| `ReservationExpired` | Lambda Expiración | Disponibilidad, Historial, Data Lake | Reserva liberada por timeout. |
| `RideStarted` | Viajes | Disponibilidad, Historial, Data Lake | Inicio de uso de un recurso. |
| `RideFinished` | Viajes | Disponibilidad, Historial, Data Lake | Finalización del viaje. |
| `VehicleBatteryLow` | Telemetría / Lambda Mantenimiento | Órdenes Mantenimiento, Historial, Data Lake | Detección de batería baja. |
| `VehicleWithdrawnForMaintenance` | Órdenes Mantenimiento | Disponibilidad, Historial, Data Lake | Recurso retirado de la vía pública. |
| `VehicleReturnedToService` | Órdenes Mantenimiento | Disponibilidad, Historial, Data Lake | Recurso vuelve a estar disponible. |
| `DemandHotspotDetected` | Analítica / Lambda Recolocación | Órdenes Recolocación, Data Lake | Zona con más demanda que recursos. |
| `RelocationOrderCreated` | Órdenes Recolocación | App Mantenimiento, Historial, Data Lake | Orden para mover recursos entre zonas. |
| `RelocationOrderCompleted` | Órdenes Recolocación | Disponibilidad, Historial, Data Lake | Recolocación finalizada. |