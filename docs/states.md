# Máquina de Estados del Recurso (Disponibilidad)

**Availability es la única dueña del estado del recurso y valida toda transición.** Los demás servicios la solicitan, por API (reservar, iniciar viaje, mantenimiento, recolocación) o por evento (liberar tras cancelar, expirar o terminar un viaje). Una transición que no figura en esta tabla se rechaza con `409 Conflict`. Ver [adr-002-reservations.md](adr-002-reservations.md).

| Estado Actual | Evento / Acción que provoca la transición | Nuevo Estado | Servicio que la provoca |
| :--- | :--- | :--- | :--- |
| *Ninguno (Nuevo)* | `VehiclePurchased` | `AVAILABLE` | Availability (escucha a Inventory) |
| `AVAILABLE` | El usuario reserva el recurso | `RESERVED` | Reservations (llamada síncrona) |
| `RESERVED` | `ReservationCancelled` | `AVAILABLE` | Reservations (evento) |
| `RESERVED` | `ReservationExpired` | `AVAILABLE` | Reservations, a petición de la Lambda Expiración (evento) |
| `RESERVED` | El usuario inicia el viaje con su reserva | `IN_USE` | Rides (llamada síncrona) |
| `AVAILABLE` | El usuario inicia el viaje sin reserva | `IN_USE` | Rides (llamada síncrona) |
| `IN_USE` | `RideFinished` con batería por encima del umbral | `AVAILABLE` | Rides (evento) |
| `IN_USE` | `RideFinished` con batería por debajo del umbral | `LOW_BATTERY` | Rides (evento) |
| `AVAILABLE` | `VehicleBatteryLow` o avería | `LOW_BATTERY` | Lambda Detección Mantenimiento |
| `LOW_BATTERY` | App Mantenimiento retira el recurso | `WITHDRAWN` | Órdenes Mantenimiento |
| `AVAILABLE` | Orden de recolocación en curso (se recoge el recurso) | `WITHDRAWN` | Órdenes Recolocación |
| `WITHDRAWN` | Mantenimiento inicia carga | `CHARGING` | Órdenes Mantenimiento |
| `WITHDRAWN` | Mantenimiento inicia reparación | `REPAIRING` | Órdenes Mantenimiento |
| `CHARGING` / `REPAIRING` | Tarea finalizada | `READY_FOR_SERVICE` | Órdenes Mantenimiento |
| `WITHDRAWN` | `RelocationOrderCompleted` (se deja en la zona destino) | `READY_FOR_SERVICE` | Órdenes Recolocación |
| `READY_FOR_SERVICE` | Recurso colocado en calle (`VehicleReturnedToService`) | `AVAILABLE` | Órdenes Mantenimiento |
| `WITHDRAWN` / `REPAIRING` | Fin de vida útil | `RETIRED` | Órdenes Mantenimiento |

## Reglas

*   **Viaje en curso:** un recurso `IN_USE` no cambia de estado hasta que termina el viaje, aunque la Lambda detecte batería baja. Al recibir `RideFinished`, Availability decide entre `AVAILABLE` y `LOW_BATTERY` según el `batteryLevelPercent` del evento. El umbral es configurable (por ejemplo, 20 %).
*   **Baja definitiva:** `RETIRED` es un estado final y solo se alcanza desde `WITHDRAWN` o `REPAIRING`. Un recurso `RESERVED` o `IN_USE` nunca puede darse de baja directamente.
*   **Recolocación:** el recurso se retira de la vía pública mientras se mueve (`WITHDRAWN`), y al dejarlo en la zona destino pasa por `READY_FOR_SERVICE` antes de volver a `AVAILABLE`. Availability actualiza también su zona actual.
*   **Zona actual:** Availability guarda, junto al estado, la zona en la que está el recurso. La actualiza con `VehiclePurchased` (zona inicial), `RideFinished` (zona destino) y `RelocationOrderCompleted` (zona destino de la orden).