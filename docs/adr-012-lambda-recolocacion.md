# ADR 012: Detección de zonas con déficit con una Lambda programada

**Estado:** propuesta. Todavía sin implementar: este documento es el contrato que hay que acordar antes de programar `lambdas/relocation`. Depende del endpoint de demanda de analytics (`docs/apis/api-analytics.md`, también en propuesta). Falta confirmar con el profesor que las Lambdas pueden estar escritas en Java (ver ADR-007).

**Problema:**
El enunciado exige que el sistema genere al menos una orden de recolocación **a partir de indicadores de demanda** que salgan de los data marts (secciones 2, 8.3 y 14). Órdenes Recolocación ya crea la orden al recibir `DemandHotspotDetected` (`docs/apis/api-relocation-orders.md`), pero nadie emite ese evento. Hay que decidir quién lo detecta, de dónde saca la demanda y los disponibles, cada cuánto se ejecuta y qué pasa cuando algo falla.

La regla es la del enunciado (8.3): si una zona tiene **menos de tres** recursos disponibles y **más de diez** intentos de reserva o viajes iniciados en la última hora, se genera una orden para traer recursos desde una zona con exceso. Se aplica por zona y por tipo de vehículo, porque una zona sin patinetes no se arregla con bicis.

**Alternativas consideradas:**

*Sobre de dónde sale la demanda:*
*   *Leer `raw/` del Data Lake directamente:* no depende de nadie, pero se salta `trusted/`, `curated/` y el data mart de demanda, que es justo de donde el enunciado pide que salga la decisión. Además hay un fichero por evento (ADR-010), así que la Lambda leería cientos de objetos en cada ejecución.
*   *Endpoints de recuento en Reservations y Rides:* respeta la propiedad de los datos, pero tampoco usa el data mart, obliga a tocar dos servicios y a guardar zona y tipo en sus tablas.
*   *Leer `analytics_db` directamente:* usa el data mart, pero los data marts los consulta analytics (`docs/data-ownership.md`) y habría que dar a la Lambda credenciales de la base. Es la misma razón por la que el ADR-007 descarta leer la base de Reservations.
*   *Preguntar al endpoint de demanda de analytics (elegida):* usa el data mart y no toca la base de nadie.

*Sobre cómo se dispara:*
*   *Una Lambda sobre una cola, como la de detección de mantenimiento (ADR-011):* reaccionaría a cada reserva, pero la regla mira una hora entera de demanda, no un evento suelto, y el evento no dice cuántos disponibles hay.
*   *Una tarea `@Scheduled` en Órdenes Recolocación:* con varias réplicas se ejecutaría en todas a la vez y no contaría como Lambda (mismas razones que en el ADR-007).
*   *Lambda programada con EventBridge (elegida).*

**Decisión:**
*   **Una Lambda en Java 21 (`lambdas/relocation`)**, disparada por **EventBridge con `rate(5 minutes)`**. La demanda de una hora cambia despacio: ejecutarla cada minuto daría casi siempre el mismo resultado con cinco veces más llamadas. A cambio, un hotspot puede tardar hasta 5 minutos en detectarse, lo que no importa para mover vehículos, que tarda mucho más.
*   **En cada ejecución hace tres llamadas**, todas con timeout de 5 s:
    1.  `GET /api/v1/analytics/demand?windowMinutes=60` a analytics: peticiones por zona y tipo, con la ventana usada y `dataUpTo`.
    2.  `GET /api/v1/availability?type=BIKE` y `GET /api/v1/availability?type=SCOOTER` a Availability: los recursos `AVAILABLE` ahora mismo, que la Lambda agrupa por zona. Es la misma consulta que ya hace Órdenes Recolocación (`AvailabilityClient.countAvailableByZone`).
*   **Las zonas se recorren desde la demanda, no desde los disponibles.** La lista de Availability solo trae los recursos disponibles, así que una zona con **0 disponibles no aparece en ella**, y es justo la que más interesa. Por eso la Lambda parte de cada zona y tipo que devuelve analytics y busca sus disponibles; si la zona no está en la lista, cuenta 0.
*   **Regla:** hay hotspot si `requests > REQUESTS_ABOVE` y `disponibles < AVAILABLE_BELOW`, con comparaciones estrictas como en el enunciado. Los umbrales llegan por variable de entorno desde Terraform, por defecto **10 y 3**, para poder bajarlos en una demo sin tocar código.
*   **Datos al día:** si `dataUpTo` es `null` o tiene más de `MAX_DATA_AGE_MINUTES` (por defecto 30), la Lambda no detecta nada y termina con error. Contar como "poca demanda" lo que en realidad es un data mart parado escondería el fallo, y hay que verlo como invocación fallida. Para que esto funcione, `dataUpTo` no es el `timestamp` del último evento, sino el momento hasta el que el data mart está procesado, y avanza en cada construcción aunque no haya eventos nuevos (`docs/apis/api-analytics.md`). Si fuera el del último evento, una madrugada sin reservas ni viajes haría que la Lambda diera error cada 5 minutos con el data mart perfectamente al día, y una alarma que salta todas las noches acaba ignorándose justo cuando el fallo es real. Con esta definición, `dataUpTo` reciente y pocas peticiones es poca demanda de verdad; `dataUpTo` viejo es que el data mart ha dejado de construirse.
*   **El evento que publica**, uno por cada zona y tipo con hotspot:

| Campo | Valor |
| :--- | :--- |
| `eventId` | UUID aleatorio |
| `timestamp` | Hora de la ejecución |
| `source` | `lambda-relocation` |
| `correlationId` | El de la ejecución, `lambda-relocation-<uuid>`, que también va como `X-Correlation-Id` en las tres llamadas |
| `payload` | `zoneId`, `vehicleType`, `availableCount` (el de Availability), `requestsLastHour` (el `requests` de analytics), y `windowStart` y `windowEnd` tal como los devuelve analytics |

*   **Se publica directo en SNS, sin outbox,** porque una Lambda no tiene base de datos (igual que el ADR-011). Añade el atributo `eventType` como el relay.
*   **Repetir un hotspot es inofensivo.** Mientras la zona siga con déficit, la Lambda emitirá un `DemandHotspotDetected` nuevo cada 5 minutos. Órdenes Recolocación no abre una segunda orden, porque solo puede haber **una activa por zona destino y tipo** (índice único parcial, `docs/apis/api-relocation-orders.md`). Y si la orden anterior se completó o se canceló y el déficit sigue, la siguiente ejecución crea otra: es lo que queremos. Por eso el `eventId` es aleatorio. Con uno determinista (por zona, tipo y ventana), `processed_events` descartaría esa segunda detección dentro de la misma ventana.
*   **Token de servicio** `svc-relocation` con rol ADMIN, firmado con el secreto JWT compartido y válido dos minutos (ADR-006, como en el ADR-007). Lo aceptan analytics y Availability.
*   **Fallos:** si analytics o Availability no responden o devuelven algo distinto de 200, la Lambda no publica nada y termina con error. No se pierde nada: el déficit sigue ahí y la ejecución de 5 minutos después lo vuelve a calcular desde cero. Si falla un `Publish` a SNS, la Lambda sigue con los demás hotspots y termina con error. Terraform desactiva los reintentos automáticos de Lambda (`maximum_retry_attempts = 0`), por la misma razón que el ADR-007: la siguiente ejecución ya lo repite.
*   **Variables de entorno:** `ANALYTICS_URL` (Terraform `analytics_url`, por defecto `http://analytics:8090`), `AVAILABILITY_URL` (por defecto `http://availability:8083`), `TOPIC_ARN`, `URBANMOVE_JWT_SECRET`, `REQUESTS_ABOVE`, `AVAILABLE_BELOW`, `MAX_DATA_AGE_MINUTES` y, solo en local, `SNS_ENDPOINT`. Se despliega con un interruptor `deploy_relocation_lambda`, como las demás.

**Qué cubre y qué no:**
*   *La Lambda detecta, no decide.* Solo dice "en esta zona falta". Qué zona cede los recursos y cuántos lo decide Órdenes Recolocación, que vuelve a contar los disponibles al recibir el evento porque el `availableCount` puede tener minutos (`AvailabilityClient.java:41`).
*   *"Intentos de reserva":* solo se cuentan las reservas que salieron bien (`VehicleReserved`). Una reserva rechazada porque el recurso ya estaba reservado no emite evento. Si se quisiera contar, Reservations tendría que publicar un evento nuevo de reserva rechazada.
*   *Requisito previo:* `VehicleReserved` y `RideStarted` tienen que llevar `vehicleType`, porque hoy no dicen si era bici o patinete (ver `docs/apis/api-analytics.md`).

**Consecuencias:**
*   *Positivas:* la cadena que pide el enunciado queda completa: eventos, Data Lake, data mart de demanda, Lambda, evento y orden. La Lambda no guarda estado ni toca ninguna base de datos, se puede probar con servidores HTTP locales, sin AWS, y vuelve a calcular todo en cada ejecución, así que un fallo se arregla solo en la siguiente.
*   *Para el flujo de fallo controlado (11.5):* si analytics se cae, no se crean órdenes nuevas, pero reservas y viajes siguen funcionando, y al volver analytics la siguiente ejecución detecta los hotspots pendientes.
*   *Negativas:* la detección va por detrás de la realidad: hasta 5 minutos de la ejecución, más lo que tarde el data mart en ponerse al día (`dataUpTo`). Mientras dure el déficit se publica un evento cada 5 minutos que solo sirve para el Data Lake. Igual que en el ADR-007, la Lambda necesita alcanzar analytics y Availability por red: en LocalStack, por la red de Docker Compose; en AWS real, sigue pendiente.
*   *Riesgo:* depende de que analytics y el data mart de demanda estén listos. Si el 6 de diciembre no lo están, el plan B es que la Lambda lea `raw/` directamente. Para que ese cambio sea pequeño, solo una clase (`DemandSource`) sabe de dónde sale la demanda; la regla, el evento y sus tests no cambiarían.
*   *Pendiente de implementar:* `vehicleType` en `VehicleReserved` y `RideStarted`, el módulo `lambdas/relocation` con sus tests, la regla de EventBridge y el bloque de la Lambda en `lambdas.tf`, y el interruptor `deploy_relocation_lambda`.
