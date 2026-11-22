# ADR 011: Detección de mantenimiento con una Lambda sobre SQS

**Estado:** propuesta. Todavía sin implementar: este documento es el contrato que hay que acordar antes de programar `lambdas/detection`. Falta confirmar con el profesor que las Lambdas pueden estar escritas en Java (ver ADR-007).

**Problema:**
Cuando un recurso termina un viaje con poca batería tiene que acabar en una orden de mantenimiento. Hoy Availability ya lo deja en `LOW_BATTERY` al recibir `RideFinished` (`docs/states.md`), pero nadie emite `VehicleBatteryLow`, que es el evento que crea la orden en Órdenes Mantenimiento y llega a Historial y al Data Lake. Hay que decidir quién lo detecta, cómo se publica sin base de datos y qué pasa si el mensaje se repite o algo falla por el camino.

**Alternativas consideradas:**
*   *Que Rides emita `VehicleBatteryLow` con su outbox:* es lo más corto, pero Viajes pasaría a conocer una regla de mantenimiento y el enunciado pide una Lambda de detección conectada a eventos reales.
*   *Que Availability lo emita:* ya conoce el umbral, pero mezclaría en un servicio de estado una regla de mantenimiento y tendría que publicar eventos, que hoy no hace.
*   *Que la Lambda llame a la API de Órdenes Mantenimiento para crear la orden:* acopla las dos y se pierde el evento para Historial y el lago. Además el contrato de Órdenes Mantenimiento no tiene un endpoint para crear órdenes a propósito (`docs/apis/api-maintenance-orders.md`).
*   *Lambda invocada por SNS directamente, sin cola:* si la Lambda falla o hay un pico de viajes no hay dónde esperar ni DLQ donde revisar (misma razón que en ADR-010).
*   *Lambda con event source mapping sobre su propia cola, filtrada a `RideFinished` (elegida).*

**Decisión:**
*   **Una Lambda en Java 21 (`lambdas/detection`) conectada a una cola propia, `urbanmove-maintenance-detection`,** con un event source mapping igual que el de la ingesta (ADR-010): lotes de hasta 10, `ReportBatchItemFailures`, DLQ tras 3 intentos y tiempo de visibilidad de 180 s. La cola se crea añadiendo `maintenance-detection` a `var.consumers`.
*   **La suscripción filtra por `eventType = RideFinished`.** El relay de libs/messaging manda ese atributo en cada mensaje, así que la cola no recibe el resto de eventos del sistema.
*   **Regla:** si `batteryLevelPercent < LOW_BATTERY_THRESHOLD` (por defecto 20, el mismo valor y la misma comparación estricta que usa Availability), la Lambda publica `VehicleBatteryLow` con `reason = BATTERY_LOW`. Si no, descarta el mensaje y lo da por bueno. El umbral llega a la Lambda por variable de entorno, desde la misma variable de Terraform y de compose que alimenta a Availability, para que los dos no discrepen.
*   **El evento que publica:**

| Campo del sobre | Valor |
| :--- | :--- |
| `eventId` | UUID determinista del `eventId` del `RideFinished` de origen (`UUID.nameUUIDFromBytes("VehicleBatteryLow:" + eventId)`) |
| `timestamp` y `payload.detectedAt` | `endTime` del viaje, no la hora a la que corre la Lambda |
| `source` | `lambda-maintenance-detection` |
| `correlationId` | El del `RideFinished`: viaje, detección, orden y retirada se siguen con un solo identificador |
| `payload` | `vehicleId` y `batteryLevelPercent` del viaje, `reason = BATTERY_LOW` |

*   **Se publica directo en SNS, sin outbox,** porque una Lambda no tiene base de datos. Como el `eventId` y el contenido salen solo del mensaje de origen, volver a procesar un mismo `RideFinished` produce el mismo evento exacto. La Lambda añade el atributo `eventType`, igual que el relay, para que cualquier suscripción filtrada futura lo reciba.
*   **La entrega duplicada es inofensiva en cada destino:** Órdenes Mantenimiento descarta los `eventId` repetidos (`processed_events`) y, aunque no lo hiciera, el índice único le impide abrir dos órdenes activas para un recurso. Historial hace lo mismo. En el Data Lake el nombre del fichero es el `eventId` (ADR-010), así que se sobrescribe el mismo objeto.
*   **No llama a Availability.** El recurso ya queda en `LOW_BATTERY` cuando Availability consume el mismo `RideFinished` con el mismo umbral, y no importa cuál de los dos lo procese antes: la orden espera `OPEN` hasta que un operario la retira (`docs/apis/api-maintenance-orders.md`), y `AVAILABLE → WITHDRAWN` también es una transición válida. Por eso la Lambda no necesita token de servicio ni secreto JWT.
*   **Variables de entorno:** `TOPIC_ARN` (el tópico `urbanmove-events`), `LOW_BATTERY_THRESHOLD` y, solo en local, `SNS_ENDPOINT` (LocalStack). Se despliega con un interruptor `deploy_detection_lambda`, como las otras dos.
*   **Fallos por mensaje:** si publicar en SNS falla, el mensaje vuelve a la cola y, tras 3 intentos, pasa a `urbanmove-maintenance-detection-dlq`. Un mensaje que no es un `RideFinished` válido se trata igual.

**Qué cubre y qué no:**
El enunciado pide detectar batería baja, avería o demasiados usos. Esta decisión cubre **solo la batería baja tras un viaje**. `FAULT` y `USAGE` siguen siendo motivos válidos en el evento y en Órdenes Mantenimiento, pero la Lambda no los genera:
*   *Avería:* no hay un servicio de incidencias que la reporte (es una ampliación del proyecto). Cuando exista, emitiría un evento de incidencia que esta Lambda, o una hermana, convertiría en `VehicleBatteryLow` con `reason = FAULT`.
*   *Demasiados usos:* hace falta contar viajes por recurso desde su última revisión, y una Lambda no guarda estado. Habría que añadir una tabla (por ejemplo DynamoDB) con un contador que se pone a cero con `VehicleReturnedToService`. Es la ampliación más natural si sobra tiempo.

**Consecuencias:**
*   *Positivas:* detección y publicación quedan desacopladas de Viajes y de Availability. Ningún `RideFinished` se pierde mientras la Lambda esté caída: esperan en la cola. El flujo de mantenimiento (11.2) se sigue de principio a fin con un solo `correlationId`.
*   *Para el flujo de fallo controlado (11.5):* parar la Lambda es un fallo de un componente no crítico muy visible. Los viajes siguen terminando, los eventos se acumulan en la cola, y al volver a arrancarla las órdenes aparecen sin intervención.
*   *Negativas:* el umbral vive en dos sitios, la Lambda y Availability, y hay que cambiarlos juntos. Si se llegara a distinguir entre umbrales por tipo de recurso, la regla tendría que moverse a un único lugar.
*   *Limitaciones:* sin FAULT ni USAGE, la Lambda cubre el mínimo del enunciado (una de las tres causas). LocalStack no valida permisos de IAM, así que un fallo de `sns:Publish` del rol solo aparecería en AWS Academy.
*   *Pendiente de implementar:* el módulo `lambdas/detection` con sus tests, la cola y el `filter_policy` en `infra/terraform`, el bloque de la Lambda en `lambdas.tf` y el interruptor `deploy_detection_lambda`.
