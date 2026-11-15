# ADR 010: Ingesta de eventos al Data Lake con una Lambda sobre SQS

**Estado:** aceptada. Implementada en `lambdas/ingestion` y `infra/terraform/lambdas.tf`. Cubre la capa `raw/`; `trusted/` y `curated/` quedan para la fase 3.

**Problema:**
El Data Lake debe guardar todo lo que ocurre en el sistema para analizarlo después. Todos los eventos ya llegan a la cola `urbanmove-datalake-ingest` (la suscripción de SNS recibe cada evento, ver ADR-005), pero nadie los copiaba a S3. Hay que decidir quién lee la cola, cómo se guardan los eventos y qué pasa cuando uno falla.

**Alternativas consideradas:**
*   *Que un microservicio lea la cola y escriba en S3:* funciona, pero ningún servicio es dueño del Data Lake (`docs/data-ownership.md`) y se mezclaría una tarea de analítica con la lógica de negocio. Además el enunciado pide Lambdas.
*   *Lambda invocada por SNS directamente (sin cola):* se pierde la cola intermedia. Si la Lambda falla o hay un pico de eventos, no hay dónde esperar ni dead-letter queue donde revisar los fallos.
*   *Un proceso que lea la cola cada cierto tiempo (la Lambda consulta SQS):* hay que escribir el sondeo, la gestión de los lotes y el borrado a mano, y la Lambda corre aunque la cola esté vacía.
*   *Lambda con event source mapping sobre la cola (elegida):* Lambda lee la cola, entrega los mensajes en lotes de hasta 10 y solo se ejecuta cuando hay mensajes.

**Decisión:**
*   **Una Lambda en Java 21 (`lambdas/ingestion`) conectada a `urbanmove-datalake-ingest` con un event source mapping** (lotes de hasta 10, espera de hasta 5 s para juntarlos). Se despliega con `deploy_ingest_lambda = true`, igual que la de expiración.
*   **Raw es una copia fiel:** el cuerpo del mensaje se guarda tal como llegó, sin transformarlo. Lo único que se comprueba es que sea un evento con su sobre completo, porque sin `eventId`, fecha y tipo no se puede colocar. Validar y normalizar es trabajo de `trusted/`.
*   **Clave del objeto:** `raw/event_date=AAAA-MM-DD/event_type=<tipo>/source=<servicio>/<eventId>.json`. Es la partición por fecha, tipo y servicio que pide el enunciado, con el formato `clave=valor` que reconocen Athena, Glue y Spark. La fecha es la del evento (`timestamp`), no la de ingesta, y el valor de cada partición se limpia para que un evento no pueda alterar la ruta.
*   **Idempotente por construcción:** el nombre del fichero es el `eventId`. SQS entrega al menos una vez (ADR-005), pero una entrega duplicada escribe la misma clave y deja el mismo objeto. No hace falta una tabla de eventos procesados, que la Lambda tampoco tiene.
*   **Fallos por mensaje, no por lote:** la Lambda responde con los mensajes que fallaron (`ReportBatchItemFailures`). SQS borra los que salieron bien y solo reentrega los fallidos; tras 3 intentos (`max_receive_count`) pasan a `urbanmove-datalake-ingest-dlq`, donde se pueden revisar. Un mensaje malo no hace repetir las otras nueve escrituras ni se pierde.
*   **El tiempo de visibilidad de la cola es de 180 s** (seis veces el timeout de 30 s de la función, lo que AWS recomienda). SQS rechaza el mapping si es menor que el timeout de la función.
*   **En local**, la Lambda recibe `DATALAKE_ENDPOINT=http://localstack:4566` y pide los objetos por ruta, porque LocalStack no resuelve nombres de bucket como dominios. En AWS esa variable no existe y usa el S3 real.

**Consecuencias:**
*   *Positivas:* ningún evento se pierde mientras la Lambda esté caída o falle: esperan en la cola y, si no se pueden guardar, en la DLQ. Los duplicados no ensucian el lago. Un lote con un mensaje malo no se repite entero. El formato de particiones queda listo para consultas por fecha y tipo.
*   *Negativas:* un objeto por evento produce muchos ficheros pequeños, que es ineficiente para analítica a gran escala. La capa `trusted/` tendrá que compactarlos (por ejemplo, en ficheros por hora). Un mensaje que no es un evento válido tarda tres intentos en llegar a la DLQ aunque nunca pueda tener éxito.
*   *Limitaciones:* LocalStack no valida permisos de IAM, así que un fallo de permisos de la Lambda sobre S3 o SQS solo aparecería en AWS Academy. Tampoco se ha medido el rendimiento.
*   *Pendiente:* confirmar con el profesor que las Lambdas pueden ser en Java (ver ADR-007) y definir cómo se limpia la capa `raw/` (por ejemplo, con una regla de ciclo de vida de S3).
