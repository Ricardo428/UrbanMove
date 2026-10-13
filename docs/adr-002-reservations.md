# ADR 002: Reserva atómica en Availability y liberación por eventos

**Estado:** propuesta, pendiente de confirmar por los dos miembros del grupo.

**Problema:**
Dos usuarios pueden intentar reservar o iniciar el viaje con el mismo recurso a la vez. Si Availability se enterase de la reserva por un evento (`VehicleReserved`), habría un intervalo en el que el recurso sigue figurando como `AVAILABLE` y las dos peticiones tendrían éxito. Hay que garantizar que un recurso no se reserve dos veces, y a la vez no bloquear el sistema si un servicio falla.

**Alternativas consideradas:**
*   *Estado por evento:* Reservations comprueba su propia base de datos y publica `VehicleReserved`; Availability actualiza el estado cuando lo recibe. Es totalmente asíncrono, pero no evita la doble reserva.
*   *Bloqueo distribuido o saga con compensación:* un bloqueo externo (por ejemplo, en Redis) o varios pasos con deshacer. Evitan la doble reserva, pero añaden un componente o una complejidad que el proyecto no necesita.
*   *Llamada síncrona con actualización condicional (elegida):* Reservations pide a Availability el cambio de estado y Availability lo aplica con una sola sentencia atómica sobre su base de datos.

**Decisión:**
*   **Reservar e iniciar viaje son síncronos.** Reservations y Rides llaman por API a Availability (`POST /api/v1/availability/{vehicleId}/reserve` y `.../start`). Availability aplica el cambio con una sentencia condicional:

    ```sql
    UPDATE vehicle_state
       SET status = 'RESERVED', reservation_id = :reservationId
     WHERE vehicle_id = :vehicleId AND status = 'AVAILABLE';
    ```

    Si la sentencia modifica una fila, responde `200`; si no modifica ninguna, responde `409 Conflict`. PostgreSQL serializa las escrituras sobre la misma fila, así que solo una de las dos peticiones simultáneas gana.
*   **Idempotencia:** la llamada lleva el `reservationId`. Si se repite con el mismo identificador, Availability responde `200` sin cambiar nada. Así un reintento tras un timeout no produce un error falso.
*   **Orden en Reservations:** primero guarda la reserva como `PENDING`, después llama a Availability y, si responde `200`, publica `VehicleReserved`. Si Availability responde `409`, marca la reserva como `REJECTED` y devuelve `409` al usuario. Si no responde a tiempo, devuelve `503` y deja la reserva `PENDING`; la Lambda de expiración la cierra y libera el recurso pasado el plazo.
*   **Liberar es asíncrono.** Cancelar, expirar o terminar un viaje se comunica por eventos (`ReservationCancelled`, `ReservationExpired`, `RideFinished`). Liberar tarde es inocuo: el recurso tarda unos instantes en volver a verse disponible. Availability aplica la liberación solo si el `reservationId` coincide, para no liberar un recurso reservado por otro.
*   **Un único validador:** Availability es la dueña del estado y valida toda transición según [states.md](states.md). Mantenimiento y recolocación piden sus cambios con `PUT /api/v1/availability/{vehicleId}/state`.

**Consecuencias:**
*   *Positivas:* no hay doble reserva con una sola fila y sin infraestructura extra. El comportamiento es fácil de probar con dos peticiones concurrentes. Las liberaciones toleran fallos y duplicados porque son idempotentes.
*   *Negativas:* Reservations y Rides dependen de que Availability esté disponible en el momento de reservar o iniciar un viaje. Se mitiga con timeout, error `503` explícito y reintentos. Si Availability cae, no se pueden hacer reservas nuevas, pero sí terminar los viajes en curso, porque `RideFinished` se encola en el bus.
*   *Pendiente:* decidir el plazo de expiración (en la documentación de la API se usan 5 minutos para una reserva sin confirmar y 15 para una confirmada) y el umbral de batería baja.
