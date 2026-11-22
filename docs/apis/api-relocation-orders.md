# Contrato de API REST: Servicio Relocation Orders

Gestiona las órdenes de recolocación: mover recursos de una zona con exceso a otra con déficit. Las órdenes las crea el propio servicio al recibir `DemandHotspotDetected` y las ejecuta un operario desde la App de Mantenimiento. Todas las peticiones llevan `Authorization: Bearer <token_jwt>`. El `operatorId` se extrae del token, nunca del cuerpo.

**Estados de una orden:** `OPEN` (creada, nadie la ha recogido), `IN_PROGRESS` (un operario ha retirado sus recursos), `COMPLETED` (los recursos están en la zona destino) y `CANCELLED` (ya no hace falta). `COMPLETED` y `CANCELLED` son finales. Solo se cancela desde `OPEN`: una orden con recursos retirados se termina. Cada cambio de estado es un `UPDATE` con el estado actual en el `WHERE`, para que dos operarios no recojan la misma orden.

```
            pickup                    complete
  OPEN ─────────────▶ IN_PROGRESS ─────────────▶ COMPLETED
    │
    │ cancel
    ▼
CANCELLED
```

**Estado de los recursos:** lo guarda y lo valida Availability ([states.md](../states.md), [adr-002-reservations.md](../adr-002-reservations.md)). Relocation Orders lo pide con `PUT /api/v1/availability/{vehicleId}/state`, de forma síncrona, porque el operario necesita saber en el momento si puede llevarse cada recurso. Un recurso de una orden `IN_PROGRESS` está `WITHDRAWN`.

**Tokens:** el token del operario dice quién llama (rol `MAINTENANCE`). Para hablar con Availability, el servicio usa su token de servicio con rol `ADMIN` ([adr-006-token-de-servicio.md](../adr-006-token-de-servicio.md)), también al crear la orden desde el consumidor, donde no hay ningún usuario.

**Formato de error:**
```json
{
  "error": "CONFLICT",
  "message": "La orden ya no está OPEN: otro operario la recogió",
  "correlationId": "c-2f6b7c18"
}
```

## Cómo nace una orden

No hay endpoint para crear órdenes. Al consumir `DemandHotspotDetected`:

1. **Consulta a Availability** `GET /api/v1/availability?type=<vehicleType>` (sin `zoneId`) y cuenta los recursos `AVAILABLE` de cada zona. Usa este recuento y no el `availableCount` del evento, que puede tener minutos.
2. **Elige el origen (`fromZoneId`):** la zona distinta de la destino con más recursos disponibles de ese tipo.
3. **Calcula `quantity`:** lo que falta en el destino para llegar a `relocation.target-available`, sin dejar el origen por debajo de `relocation.min-left-in-origin`:
   `quantity = min(target-available − disponibles en destino, disponibles en origen − min-left-in-origin)`.
   Si sale 0 o menos, no se crea la orden y se registra en el log.
4. **Inserta la orden `OPEN`** con `INSERT ... ON CONFLICT DO NOTHING`. Un índice único parcial sobre (`to_zone_id`, `vehicle_type`) entre las órdenes `OPEN` o `IN_PROGRESS` impide tener dos órdenes activas para el mismo hueco, aunque dos réplicas procesen a la vez dos hotspots distintos. Si inserta 0 filas, ya había una orden activa: no se emite nada.
5. **Publica `RelocationOrderCreated`** en el outbox, en la misma transacción que la orden y la marca de evento procesado.

Si Availability no responde, el handler falla, se deshace la transacción y SQS vuelve a entregar el mensaje. Tras 3 intentos va a la DLQ. Un mismo `DemandHotspotDetected` repetido lo frena `processed_events`.

**Configuración:**

| Propiedad | Valor por defecto | Significado |
| :--- | :--- | :--- |
| `urbanmove.relocation.target-available` (`RELOCATION_TARGET_AVAILABLE`) | `6` | Recursos disponibles que se quieren tener en la zona destino. |
| `urbanmove.relocation.min-left-in-origin` (`RELOCATION_MIN_LEFT_IN_ORIGIN`) | `2` | Recursos disponibles que deben quedar en la zona origen. |

**Limitación conocida:** una orden coge recursos de una sola zona origen y solo hay una orden activa por zona destino y tipo. Si una zona no basta para llegar a `target-available`, la orden se crea con lo que esa zona puede ceder. Cuando se completa, el siguiente `DemandHotspotDetected` de esa zona crea otra orden, eligiendo de nuevo la zona origen con más recursos. Así el destino se acerca al objetivo en varias órdenes, sin vaciar ningún origen por debajo del mínimo y sin enviar recursos de más. Crear varias órdenes en paralelo para el mismo destino obligaría a descontar los recursos que ya van en camino.

## 1. Listar Órdenes
**Endpoint:** `GET /api/v1/relocation-orders?status=OPEN` (rol `MAINTENANCE` o `ADMIN`) \
**Descripción:** Devuelve las órdenes, las más antiguas primero. El filtro `status` es opcional. Es la pantalla principal de la App de Mantenimiento.

**Respuesta Exitosa (200 OK):**
```json
[
  {
    "orderId": "ro-210",
    "fromZoneId": "zone-norte",
    "toZoneId": "zone-centro",
    "vehicleType": "SCOOTER",
    "quantity": 4,
    "status": "OPEN",
    "createdAt": "2026-10-13T16:00:03Z"
  }
]
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `status` no es uno de los cuatro estados. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |

**Evento emitido:** ninguno.

## 2. Consultar Orden
**Endpoint:** `GET /api/v1/relocation-orders/{orderId}` (rol `MAINTENANCE` o `ADMIN`) \
**Descripción:** Devuelve una orden con su operario y los recursos retirados.

**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "ro-210",
  "fromZoneId": "zone-norte",
  "toZoneId": "zone-centro",
  "vehicleType": "SCOOTER",
  "quantity": 4,
  "status": "IN_PROGRESS",
  "operatorId": "u-2001",
  "vehicleIds": ["v-0051", "v-0052", "v-0054"],
  "createdAt": "2026-10-13T16:00:03Z",
  "pickedUpAt": "2026-10-13T16:40:00Z",
  "completedAt": null
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |
| `404 Not Found` | La orden no existe. |

**Evento emitido:** ninguno.

## 3. Recoger los Recursos
**Endpoint:** `POST /api/v1/relocation-orders/{orderId}/pickup` (rol `MAINTENANCE`) \
**Descripción:** El operario recoge recursos en la zona origen. Pasos:

1. **Reclama la orden:** `OPEN → IN_PROGRESS` con `operator_id` = el usuario del token, en un `UPDATE` condicional. Si otro operario la reclamó antes, cambia 0 filas y se rechaza sin tocar ningún recurso.
2. **Retira cada recurso**, fuera de la transacción:
   1. `GET /api/v1/availability/{vehicleId}` para comprobar que es del tipo y de la zona origen de la orden, y que está `AVAILABLE` (o `WITHDRAWN` y ya reclamado por esta misma orden, si es un reintento). El `PUT /state` de Availability solo mira el estado, no la zona, y es idempotente: sin esta comprobación, un recurso que Órdenes Mantenimiento ya retiró pasaría por bueno.
   2. **Reclama el recurso** insertándolo en `relocation_order_vehicles`. Un índice único parcial impide que esté en dos órdenes activas: si otra orden lo tiene, se rechaza sin llamar a Availability. Hace falta porque el `PUT /state` es idempotente y no distingue "lo retiré yo" de "lo retiró otra orden".
   3. `PUT /api/v1/availability/{vehicleId}/state` con `WITHDRAWN`. Si Availability lo rechaza, se borra la fila reclamada. Si no responde, la fila se mantiene: no se sabe si el `PUT` se aplicó, y al repetir la petición el mismo `PUT` (idempotente) lo confirma.
3. **Resultado parcial:** si un recurso falla (por ejemplo, alguien lo reservó un instante antes), se retiran los demás y la respuesta dice cuál falló. Si no se retira ninguno y todos se rechazaron con seguridad, la orden vuelve a `OPEN`. Si alguno quedó sin respuesta de Availability, la orden sigue `IN_PROGRESS` del mismo operario para que pueda repetir la petición.

**Limitación conocida:** si Órdenes Mantenimiento retira el mismo recurso justo entre el `GET` y el `PUT`, el `PUT` responde bien porque el recurso ya está `WITHDRAWN`. Exige que pase de `AVAILABLE` a `LOW_BATTERY` y a `WITHDRAWN` en ese instante. Cerrarlo del todo requeriría que Availability guardara quién retiró cada recurso.

**Reintento:** si la orden ya está `IN_PROGRESS` con el mismo operario, la petición se repite sin error. El `PUT` es idempotente, así que un recurso ya `WITHDRAWN` no cambia, y se añaden los nuevos hasta `quantity`. Sirve si una llamada a Availability se quedó sin respuesta.

**Cuerpo de la petición (Request):**
```json
{
  "vehicleIds": ["v-0051", "v-0052", "v-0053", "v-0054"]
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "ro-210",
  "status": "IN_PROGRESS",
  "withdrawn": ["v-0051", "v-0052", "v-0054"],
  "rejected": [
    { "vehicleId": "v-0053", "reason": "El vehículo no está disponible: RESERVED" }
  ]
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `vehicleIds` está vacío o tiene más elementos que `quantity`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE`, o la orden la recogió otro operario. |
| `404 Not Found` | La orden no existe. |
| `409 Conflict` | La orden no está `OPEN` (ni `IN_PROGRESS` del mismo operario), o no se pudo retirar ningún recurso. |
| `503 Service Unavailable` | Availability no responde y no se retiró ningún recurso. La orden sigue `IN_PROGRESS` del mismo operario, con los recursos sin respuesta reclamados, y la petición se puede repetir. |

**Evento emitido:** ninguno.

## 4. Completar la Orden
**Endpoint:** `POST /api/v1/relocation-orders/{orderId}/complete` (rol `MAINTENANCE`) \
**Descripción:** El operario deja los recursos en la zona destino. Solo puede completarla quien la recogió: se compara el `operator_id` guardado en la orden con el usuario del token. Pasos:

1. Para cada recurso reclamado, `GET /api/v1/availability/{vehicleId}`, y según cómo esté:
   - `WITHDRAWN`: `PUT /state` con `READY_FOR_SERVICE` y `zoneId` = la zona destino, y después con `AVAILABLE`.
   - `READY_FOR_SERVICE` en la zona destino: solo `PUT /state` con `AVAILABLE` (un intento anterior se cortó a mitad).
   - `AVAILABLE`, `RESERVED` o `IN_USE` en la zona destino: ya se entregó en un intento anterior; no se llama al `PUT`.
   - Cualquier otro estado o zona: `409`.

   Hace falta el `GET` porque `READY_FOR_SERVICE` solo se alcanza desde `WITHDRAWN`, `CHARGING` o `REPAIRING` ([states.md](../states.md)): pedirlo otra vez para un recurso ya `AVAILABLE` no es repetir el mismo estado, y daría un `409`. Además, entre un intento y el reintento un usuario puede haber reservado el recurso ya entregado.
2. En una transacción: `IN_PROGRESS → COMPLETED` con un `UPDATE` condicional, sus recursos dejan de estar activos en `relocation_order_vehicles` (pueden entrar en otra orden), y `RelocationOrderCompleted` en el outbox.

Si falla a mitad, la orden sigue `IN_PROGRESS` y el operario repite la petición: cada recurso continúa desde el estado en que se quedó. El evento solo se emite cuando todos están entregados.

**Reintento:** si la orden ya está `COMPLETED` por el mismo operario (por ejemplo, se perdió la respuesta), se devuelve la orden sin llamar a Availability ni emitir el evento otra vez. El `UPDATE` condicional `IN_PROGRESS → COMPLETED` solo cambia una fila una vez, así que nunca hay dos `RelocationOrderCompleted` de la misma orden.

**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "ro-210",
  "status": "COMPLETED",
  "vehicleIds": ["v-0051", "v-0052", "v-0054"],
  "toZoneId": "zone-centro",
  "completedAt": "2026-10-13T17:30:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE`, o la orden la recogió otro operario. |
| `404 Not Found` | La orden no existe. |
| `409 Conflict` | La orden no está `IN_PROGRESS` (ni `COMPLETED` por el mismo operario), no tiene recursos retirados (si en `/pickup` no respondió ningún `GET`), o un recurso no está como se espera o Availability rechaza su transición. |
| `503 Service Unavailable` | Availability no responde. La orden sigue `IN_PROGRESS` y se puede repetir. |

**Evento emitido:** `RelocationOrderCompleted`.

## 5. Cancelar la Orden
**Endpoint:** `POST /api/v1/relocation-orders/{orderId}/cancel` (rol `ADMIN`) \
**Descripción:** Cancela una orden que nadie ha recogido: `OPEN → CANCELLED`. No toca ningún recurso, porque una orden `OPEN` no ha retirado ninguno. Al dejar de estar activa, un nuevo `DemandHotspotDetected` de esa zona y tipo puede crear otra orden.

**Respuesta Exitosa (200 OK):**
```json
{
  "orderId": "ro-210",
  "status": "CANCELLED"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un `ADMIN`. |
| `404 Not Found` | La orden no existe. |
| `409 Conflict` | La orden no está `OPEN`. |

**Evento emitido:** ninguno.

## Eventos que consume

| Evento | Efecto |
| :--- | :--- |
| `DemandHotspotDetected` | Crea una orden `OPEN`, salvo que ya haya una activa para esa zona destino y tipo, o que ninguna zona pueda ceder recursos. Ver "Cómo nace una orden". |

El procesamiento es idempotente: se descartan los eventos cuyo `eventId` ya se procesó.

## Eventos que emite

| Evento | Cuándo |
| :--- | :--- |
| `RelocationOrderCreated` | Al crear una orden desde `DemandHotspotDetected`. Lleva el `correlationId` del hotspot. |
| `RelocationOrderCompleted` | Al completar una orden, con los `vehicleIds` que se movieron. |

Los consumen Historial y el Data Lake ([events.md](../events.md)). Availability no los consume: el estado de los recursos ya cambió por API.
