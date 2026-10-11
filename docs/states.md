# Máquina de Estados del Recurso (Disponibilidad)

| Estado Actual | Evento / Acción que provoca la transición | Nuevo Estado | Servicio Responsable |
| :--- | :--- | :--- | :--- |
| *Ninguno (Nuevo)* | `VehiclePurchased` | `AVAILABLE` | Availability (escucha a Inventario) |
| `AVAILABLE` | El usuario reserva el recurso | `RESERVED` | Reservations (valida Availability) |
| `RESERVED` | `ReservationCancelled` | `AVAILABLE` | Reservations |
| `RESERVED` | `ReservationExpired` | `AVAILABLE` | Lambda Expiración -> Reservations |
| `RESERVED` | El usuario inicia el viaje | `IN_USE` | Rides |
| `AVAILABLE` | El usuario inicia el viaje | `IN_USE` | Rides |
| `IN_USE` | El usuario finaliza el viaje | `AVAILABLE` | Rides |
| `AVAILABLE` / `IN_USE` | `VehicleBatteryLow` o Avería | `LOW_BATTERY` | Lambda Detección Mantenimiento |
| `LOW_BATTERY` | App Mantenimiento retira el recurso | `WITHDRAWN` | Órdenes Mantenimiento |
| `WITHDRAWN` | Mantenimiento inicia carga | `CHARGING` | Órdenes Mantenimiento |
| `WITHDRAWN` | Mantenimiento inicia reparación | `REPAIRING` | Órdenes Mantenimiento |
| `CHARGING` / `REPAIRING` | Tarea finalizada | `READY_FOR_SERVICE` | Órdenes Mantenimiento |
| `READY_FOR_SERVICE` | Recurso colocado en calle | `AVAILABLE` | Órdenes Mantenimiento / Disponibilidad |
| *Cualquiera* | Fin de vida útil | `RETIRED` | Órdenes Mantenimiento |