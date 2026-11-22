# Contrato de API REST: Servicio Maintenance Orders

Gestiona las órdenes de mantenimiento: retirar de la vía pública un recurso con batería baja, avería o demasiados usos, recargarlo o repararlo, y devolverlo al servicio. Las órdenes las crea el propio servicio al recibir `VehicleBatteryLow` y las ejecuta un operario desde la App de Mantenimiento. Todas las peticiones llevan `Authorization: Bearer <token_jwt>`. El `operatorId` se extrae del token, nunca del cuerpo.

**Estados de una orden:**

| Estado | Significado | Estado del recurso en Availability |
| :--- | :--- | :--- |
| `OPEN` | Creada, nadie la ha recogido | `LOW_BATTERY` |
| `WITHDRAWN` | Un operario ha retirado el recurso de la vía pública | `WITHDRAWN` |
| `IN_PROGRESS` | El operario está recargando o reparando | `CHARGING` o `REPAIRING` |
| `COMPLETED` | El recurso ha vuelto al servicio | `AVAILABLE` |

`COMPLETED` es final. **No hay cancelación:** un recurso `LOW_BATTERY` solo sale de ese estado hacia `WITHDRAWN` ([states.md](../states.md)), así que cancelar la orden lo dejaría sin dueño y fuera del servicio. La única salida de una orden es terminarla. Cada cambio de estado es un `UPDATE` con el estado actual en el `WHERE`, para que dos operarios no recojan la misma orden.

```
          withdraw                   start                    complete
  OPEN ──────────────▶ WITHDRAWN ──────────────▶ IN_PROGRESS ──────────────▶ COMPLETED
```

**Estado de los recursos:** lo guarda y lo valida Availability ([states.md](../states.md), [adr-002-reservations.md](../adr-002-reservations.md)). Maintenance Orders lo pide con `PUT /api/v1/availability/{vehicleId}/state`, de forma síncrona, porque el operario necesita saber en el momento si el recurso se puede retirar.

**Orden de las operaciones:** en `withdraw`, `start` y `complete` el servicio **llama primero a Availability y después guarda la orden**, con el `UPDATE` condicional y el evento en la misma transacción. El `PUT /state` es idempotente (pedir el estado en el que ya está el recurso responde `200`), así que si el servicio cae entre las dos cosas, el operario repite la petición y el recurso y la orden convergen. Hacerlo al revés dejaría una orden `WITHDRAWN` con el recurso todavía en la calle.

**Tokens:** el token del operario dice quién llama (rol `MAINTENANCE`). Para hablar con Availability, el servicio usa su token de servicio con rol `ADMIN` ([adr-006-token-de-servicio.md](../adr-006-token-de-servicio.md)).

**Formato de error:**
```json
{
  "error": "CONFLICT",
  "message": "La orden ya no está OPEN: otro operario la retiró",
  "correlationId": "c-9e3340b7"
}
```

## Cómo nace una orden

No hay endpoint para crear órdenes. Al consumir `VehicleBatteryLow`, que emite la Lambda de detección de mantenimiento:

1. **Inserta la orden `OPEN`** con `vehicleId`, `reason`, `batteryLevelPercent` y `detectedAt` tomados del evento, con `INSERT ... ON CONFLICT DO NOTHING`. Un índice único parcial sobre `vehicle_id` entre las órdenes `OPEN`, `WITHDRAWN` o `IN_PROGRESS` impide tener dos órdenes activas para el mismo recurso, aunque dos réplicas procesen a la vez dos detecciones del mismo recurso. Si inserta 0 filas, ya había una orden activa y la detección se descarta.
2. **Descarta las detecciones atrasadas:** si el recurso ya tiene una orden `COMPLETED` con `completedAt` posterior a `detectedAt`, el evento llegó tarde (el bus no garantiza el orden, ver [events.md](../events.md)) y no se crea nada. Sin esta regla, una detección repetida que tardase en llegar abriría una orden para un recurso que ya está en la calle.
3. **No emite ningún evento** y **no llama a Availability**: el recurso ya está `LOW_BATTERY` (lo dejó así la Lambda o el fin de viaje), y la detección ya llega a Historial y al Data Lake como `VehicleBatteryLow`. El consumidor solo toca su base de datos, así que no depende de ningún otro servicio.

Si el `INSERT` falla, se deshace la transacción y SQS vuelve a entregar el mensaje. Tras 3 intentos va a la DLQ. Un mismo `VehicleBatteryLow` repetido lo frena `processed_events`.

**Limitación conocida:** el consumidor no comprueba el estado real del recurso. Si la orden nace con el recurso aún `IN_USE` (un viaje en curso no cambia de estado hasta terminar, ver [states.md](../states.md)), `withdraw` responde `409` hasta que el viaje acabe y el recurso pase a `LOW_BATTERY`. La orden espera `OPEN`.

## 1. Listar Órdenes
**Endpoint:** `GET /api/v1/maintenance-orders?status=OPEN&vehicleId=v-0042` (rol `MAINTENANCE` o `ADMIN`) \
**Descripción:** Devuelve las órdenes, las más antiguas primero. Los filtros `status` y `vehicleId` son opcionales. Es la pantalla principal de la App de Mantenimiento: las `OPEN` son los recursos con batería baja, avería o uso anómalo pendientes de retirar.

**Respuesta Exitosa (200 OK):**
```json
[
  {
    "orderId": "mo-310",
    "vehicleId": "v-0042",
    "reason": "BATTERY_LOW",
    "batteryLevelPercent": 14,
    "status": "OPEN",
    "detectedAt": "2026-10-13T11:00:02Z",
    "createdAt": "2026-10-13T11:00:05Z"
  }
]
```

`reason` es el del evento: `BATTERY_LOW`, `FAULT` o `USAGE`.

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `status` no es uno de los cuatro estados. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |

**Evento emitido:** ninguno.

## 2. Consultar Orden
**Endpoint:** `GET /api/v1/maintenance-orders/{orderId}` (rol `MAINTENANCE` o `ADMIN`) \
**Descripción:** Devuelve una orden con su operario, la tarea registrada y las marcas de tiempo de cada paso.

**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "mo-310",
  "vehicleId": "v-0042",
  "reason": "BATTERY_LOW",
  "batteryLevelPercent": 14,
  "status": "IN_PROGRESS",
  "task": "RECHARGE",
  "operatorId": "u-2001",
  "zoneId": null,
  "detectedAt": "2026-10-13T11:00:02Z",
  "createdAt": "2026-10-13T11:00:05Z",
  "withdrawnAt": "2026-10-13T12:00:00Z",
  "startedAt": "2026-10-13T12:05:00Z",
  "completedAt": null
}
```

`task` y `operatorId` son `null` mientras la orden está `OPEN`; `task` lo es también en `WITHDRAWN`. `zoneId` es la zona en la que queda el recurso al completar la orden y es `null` hasta entonces. Se guarda para que repetir `complete` devuelva la misma respuesta sin volver a preguntar a Availability.

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |
| `404 Not Found` | La orden no existe. |

**Evento emitido:** ninguno.

## 3. Retirar el Recurso
**Endpoint:** `POST /api/v1/maintenance-orders/{orderId}/withdraw` (rol `MAINTENANCE`) \
**Descripción:** El operario retira el recurso de la vía pública. Es la asignación: la orden queda a su nombre y solo él puede continuarla. Pasos:

1. Lee la orden. Debe estar `OPEN`, o `WITHDRAWN` por el mismo operario (reintento).
2. `PUT /api/v1/availability/{vehicleId}/state` con `WITHDRAWN` y `reason` = `Orden de mantenimiento mo-310`.
3. En una transacción: `OPEN → WITHDRAWN` con `operator_id` = el usuario del token y `withdrawn_at`, en un `UPDATE` condicional, y `VehicleWithdrawnForMaintenance` en el outbox. Si el `UPDATE` cambia 0 filas, otro operario llegó antes: se vuelve a leer la orden y se rechaza con `403`.

**Reintento:** si la orden ya está `WITHDRAWN` por el mismo operario, la petición responde `200` con la misma orden y **no emite el evento otra vez**. Solo emite quien consigue el `UPDATE`.

**Cuerpo de la petición:** ninguno.

**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "mo-310",
  "vehicleId": "v-0042",
  "status": "WITHDRAWN",
  "operatorId": "u-2001",
  "withdrawnAt": "2026-10-13T12:00:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE`, o la orden la tiene otro operario. |
| `404 Not Found` | La orden no existe. |
| `409 Conflict` | La orden no está `OPEN` (ni `WITHDRAWN` del mismo operario), o Availability rechaza la transición porque el recurso no está `LOW_BATTERY` (por ejemplo, sigue `IN_USE`). La orden no cambia. |
| `503 Service Unavailable` | Availability no responde. La orden sigue `OPEN` y se puede repetir. |

**Evento emitido:** `VehicleWithdrawnForMaintenance`, con el `reason` de la orden.

## 4. Registrar la Tarea
**Endpoint:** `POST /api/v1/maintenance-orders/{orderId}/start` (rol `MAINTENANCE`) \
**Descripción:** El operario empieza la tarea sobre el recurso retirado. Solo puede hacerlo quien lo retiró. La tarea indica qué estado pide a Availability:

| `task` | Qué es | Estado del recurso |
| :--- | :--- | :--- |
| `RECHARGE` | Recarga de batería | `CHARGING` |
| `REPAIR` | Reparación de una avería | `REPAIRING` |
| `REVIEW` | Revisión por uso | `REPAIRING` (no existe un estado propio de revisión en [states.md](../states.md)) |

Pasos: `PUT /state` con `CHARGING` o `REPAIRING` y, después, `WITHDRAWN → IN_PROGRESS` con `task` y `started_at`, en un `UPDATE` condicional que exige el mismo `operator_id`. Una orden tiene una sola tarea: si el recurso necesita otra después, el siguiente `VehicleBatteryLow` abre otra orden.

**Reintento:** si la orden ya está `IN_PROGRESS` con el mismo operario y la misma `task`, responde `200` sin cambios. Con otra `task`, `409`.

**Cuerpo de la petición (Request):**
```json
{
  "task": "RECHARGE"
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "mo-310",
  "status": "IN_PROGRESS",
  "task": "RECHARGE",
  "startedAt": "2026-10-13T12:05:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `task` falta o no es `RECHARGE`, `REPAIR` ni `REVIEW`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE`, o la orden la tiene otro operario. |
| `404 Not Found` | La orden no existe. |
| `409 Conflict` | La orden no está `WITHDRAWN` (ni `IN_PROGRESS` del mismo operario con la misma `task`), o Availability rechaza la transición. |
| `503 Service Unavailable` | Availability no responde. La orden sigue `WITHDRAWN` y se puede repetir. |

**Evento emitido:** ninguno. Historial y el Data Lake ven la tarea por la orden; el contrato de eventos no incluye un evento de inicio de recarga o reparación ([events.md](../events.md)).

## 5. Volver al Servicio
**Endpoint:** `POST /api/v1/maintenance-orders/{orderId}/complete` (rol `MAINTENANCE`) \
**Descripción:** El operario termina la tarea y deja el recurso en la calle, en la misma zona en la que estaba. Solo puede completarla quien la retiró. Pasos:

1. `PUT /api/v1/availability/{vehicleId}/state` con `READY_FOR_SERVICE` y, después, con `AVAILABLE`. La respuesta del segundo devuelve la `zoneId` del recurso. Si Availability rechaza el primero con `409` se sigue con el segundo: es lo que pasa al repetir la petición cuando el recurso ya está `AVAILABLE` (`READY_FOR_SERVICE` solo se alcanza desde `WITHDRAWN`, `CHARGING` o `REPAIRING`), y el `PUT` a `AVAILABLE` responde `200` si ya lo está y rechaza si el recurso está en otro estado.
2. En una transacción: `IN_PROGRESS → COMPLETED` con `completed_at`, en un `UPDATE` condicional, y `VehicleReturnedToService` en el outbox con esa `zoneId`. Al salir de las órdenes activas, el recurso puede abrir otra orden si vuelve a dar un `VehicleBatteryLow`.

Si falla a mitad, la orden sigue `IN_PROGRESS` y el operario repite la petición: el `PUT` es idempotente. El evento solo se emite cuando el recurso está `AVAILABLE`.

**Reintento:** si la orden ya está `COMPLETED` por el mismo operario, responde `200` con la misma `zoneId` guardada y no emite el evento otra vez.

**Cuerpo de la petición:** ninguno.

**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "mo-310",
  "vehicleId": "v-0042",
  "status": "COMPLETED",
  "zoneId": "zone-norte",
  "completedAt": "2026-10-13T15:00:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE`, o la orden la tuvo otro operario. |
| `404 Not Found` | La orden no existe. |
| `409 Conflict` | La orden no está `IN_PROGRESS` (ni `COMPLETED` por el mismo operario), o Availability rechaza la transición. |
| `503 Service Unavailable` | Availability no responde. La orden sigue `IN_PROGRESS` y se puede repetir. |

**Evento emitido:** `VehicleReturnedToService`.

## Eventos que consume

| Evento | Efecto |
| :--- | :--- |
| `VehicleBatteryLow` | Crea una orden `OPEN`, salvo que el recurso ya tenga una activa o la detección sea anterior a la última orden completada. Ver "Cómo nace una orden". |

El procesamiento es idempotente: se descartan los eventos cuyo `eventId` ya se procesó. La cola es `urbanmove-maintenance-orders`, con su DLQ.

## Eventos que emite

| Evento | Cuándo |
| :--- | :--- |
| `VehicleWithdrawnForMaintenance` | Al retirar el recurso (`withdraw`), con el `orderId`, el `reason` de la orden y `withdrawnAt`. |
| `VehicleReturnedToService` | Al completar la orden, con el `orderId`, la `zoneId` donde queda el recurso y `returnedAt`. |

Los dos llevan el `X-Correlation-Id` de la petición del operario, y los consumen Historial y el Data Lake ([events.md](../events.md)). Availability no los consume: el estado del recurso ya cambió por API. El tiempo que un recurso pasa fuera de servicio es la distancia entre estos dos eventos para el mismo `vehicleId`, y es la base del data mart de mantenimiento.

## Fuera de alcance

*   **Baja definitiva (`RETIRED`):** Availability admite `WITHDRAWN`/`REPAIRING → RETIRED`, pero este servicio no lo expone. Una baja necesitaría su propio evento y su cierre de orden.
*   **Órdenes manuales:** la App de Mantenimiento no crea órdenes. Los recursos con batería baja, avería o uso anómalo entran por `VehicleBatteryLow`. Para la demo sin la Lambda, el evento se publica a mano en el tópico.
