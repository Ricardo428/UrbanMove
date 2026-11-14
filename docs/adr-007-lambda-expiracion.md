# ADR 007: Expiración de reservas con una Lambda programada

**Estado:** propuesta. Implementada en `lambdas/expiration` y `infra/terraform/lambdas.tf`. Falta confirmar con el profesor que las Lambdas pueden estar escritas en Java.

**Problema:**
Una reserva que no se confirma (5 minutos) o que no se usa tras confirmarla (15 minutos) tiene que caducar sola y liberar el vehículo, sin que nadie lo pida. Hay que decidir quién lo dispara, cómo sabe qué reservas han vencido y cómo evita duplicar o perder la expiración. El enunciado además exige al menos tres Lambdas conectadas a eventos reales, y esta es una de las obligatorias.

**Alternativas consideradas:**
*   *Tarea programada dentro de Reservations (`@Scheduled`):* es lo más sencillo y no necesita infraestructura, pero no cumple el requisito de las Lambdas y, con varias réplicas del servicio, todas ejecutarían la tarea a la vez.
*   *Lambda que lee la base de datos de Reservations:* es la más directa, pero rompe la propiedad de los datos (`docs/data-ownership.md`): Reservations dejaría de ser la única que toca su tabla, y habría que dar a la Lambda las credenciales de la base.
*   *Mensaje retrasado en SQS al crear la reserva:* evita el sondeo, pero SQS solo retrasa hasta 15 minutos, no permite cancelar el mensaje cuando la reserva se confirma o se usa, y habría que descartar los mensajes obsoletos.
*   *Lambda programada que llama a la API de Reservations (elegida):* EventBridge la invoca cada minuto; la Lambda pregunta qué reservas han vencido y las expira una a una.

**Decisión:**
*   **EventBridge** dispara la Lambda con `rate(1 minute)`.
*   **Lambda en Java 21**, con el mismo build de Maven que el resto (ADR-001). No usa `libs/common` porque arrastraría Spring Boot al paquete; firma su token con la misma librería JWT.
*   **Dos llamadas a Reservations:** `GET /api/v1/reservations/overdue` devuelve hasta 100 identificadores y `POST /api/v1/reservations/{id}/expire` cierra cada una. Reservations cambia el estado y guarda `ReservationExpired` en su outbox en la misma transacción (ADR-005); la Lambda no publica eventos.
*   **Token de servicio** `svc-expiration` con rol ADMIN, firmado con el secreto JWT compartido y válido dos minutos, el doble del timeout de la Lambda (ADR-006). Se descartó subirlo a cinco: la Lambda nunca necesita más de un minuto y es un token ADMIN, así que cuanto menos dure, menos vale si se filtra.
*   **Idempotente:** `expire` sobre una reserva ya expirada responde 200 sin repetir el evento, y sobre una cancelada o usada responde 409, que la Lambda cuenta como "ya resuelta". Si se solapan dos ejecuciones o la Lambda se reintenta, no pasa nada.
*   **Presupuesto de tiempo:** cada llamada a Reservations tiene un timeout de 5 s y puede haber hasta 100 reservas, así que en el peor caso una ejecución tardaría mucho más que los 60 s de la Lambda, y el token podría caducar a mitad (401) o la función cortarse sin dejar constancia. Por eso la Lambda deja de empezar expiraciones a los 40 s y lo anota en el log como "aplazadas". Con la consulta inicial y la llamada en curso, el peor caso queda en unos 50 s. Las aplazadas siguen vencidas y salen en la consulta del minuto siguiente, así que no se pierde nada. Una reserva aplazada no es un error y no hace fallar la invocación.
*   **Fallos y reintentos:** si alguna reserva falla, la Lambda termina con error para que conste como invocación fallida. Las demás se procesan igualmente. EventBridge invoca la función de forma asíncrona y Lambda reintentaría dos veces por su cuenta, pero esos reintentos solo repiten las mismas llamadas y el mismo error en los logs, porque la ejecución del minuto siguiente ya vuelve a encontrar las reservas que fallaron. Terraform los desactiva con `maximum_retry_attempts = 0` (`aws_lambda_function_event_invoke_config`).
*   **Correlación:** cada ejecución genera un `X-Correlation-Id` (`lambda-expiration-<uuid>`) que aparece en los logs de la Lambda y de Reservations.

**Consecuencias:**
*   *Positivas:* la expiración funciona aunque Reservations tenga varias réplicas, no hay estado en la Lambda y se puede probar con un servidor HTTP local, sin AWS. Cumple la propiedad de los datos y reutiliza el token de servicio ya existente.
*   *Negativas:* una reserva puede tardar hasta un minuto más de lo previsto en caducar (el plazo exacto lo da el momento de la siguiente ejecución). La Lambda necesita alcanzar Reservations por red: en LocalStack se logra uniéndola a la red de Docker Compose, y en AWS real haría falta desplegar Reservations de forma accesible (ECS o similar), que sigue pendiente de decidir con el profesor. El secreto JWT viaja como variable de entorno de la Lambda y queda en el estado de Terraform, que por eso no se sube a Git. Con más de 100 reservas vencidas a la vez, o si Reservations responde muy despacio, las restantes esperan a la ejecución siguiente.
*   *Pendiente:* guardar el secreto en un almacén de secretos (Secrets Manager o SSM) si se despliega en AWS real, y una alarma si la Lambda falla varias veces seguidas.
