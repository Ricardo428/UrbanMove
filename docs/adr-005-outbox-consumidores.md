# ADR 005: Outbox transaccional y consumidores idempotentes

**Estado:** aceptada. Implementada en `libs/events-contract` y `libs/messaging`; identity ya la usa para `UserRegistered`.

**Problema:**
Cada servicio cambia su base de datos y publica un evento en SNS. Son dos sistemas distintos, así que no se pueden hacer en una sola transacción: si se guarda el cambio y SNS falla (o el servicio cae justo después), el evento se pierde; si se publica primero y la transacción falla, se anuncia algo que no ha ocurrido. Por el otro lado, SQS entrega cada mensaje al menos una vez, así que un consumidor puede recibir el mismo evento dos veces. Además, nueve servicios y varias Lambdas tienen que coincidir en el formato de los eventos.

**Alternativas consideradas:**
*   *Publicar en SNS justo después del commit:* sencillo, pero un fallo entre el commit y la publicación pierde el evento sin dejar rastro.
*   *Change Data Capture (Debezium sobre el WAL de PostgreSQL):* no toca el código de los servicios, pero añade Kafka Connect y una infraestructura que el proyecto no necesita.
*   *Spring Cloud AWS (`@SqsListener`):* ahorra el bucle de lectura, pero añade otra dependencia con su propio ciclo de versiones y oculta el manejo de reintentos y DLQ que hay que explicar en la defensa.
*   *Outbox en la base del servicio y consumidor propio (elegida).*

**Decisión:**
*   **Contrato en código (`libs/events-contract`).** Sobre común, un `enum` con los 14 eventos y su versión, y un record por payload. Solo depende de Jackson para que las Lambdas lo usen igual. Un test lee los ejemplos de [events.md](events.md) y falla si el código y la documentación no coinciden.
*   **Outbox.** `EventPublisher.publish` escribe el evento en `outbox_events` y exige una transacción activa, así que el evento y el cambio de estado se guardan juntos o no se guarda ninguno.
*   **Relay.** `OutboxRelay` lee la outbox cada segundo y publica en SNS. Usa `FOR UPDATE SKIP LOCKED`, así que varias réplicas del mismo servicio se reparten los eventos sin publicarlos dos veces a la vez. Si SNS falla, el evento se reintenta con espera exponencial (de 1 s a 5 min) y el error queda en `last_error`. Sin topic configurado, el servicio arranca igual y los eventos esperan.
*   **Consumidor.** `EventConsumer` lee la cola SQS del servicio con long polling. Por cada evento, en una transacción, inserta el `eventId` en `processed_events` (si ya estaba, lo descarta) y llama al `EventHandler`. Solo borra el mensaje si la transacción termina bien; si no, SQS lo reentrega y tras 3 intentos lo mueve a la DLQ. Un mensaje que no es un evento válido también acaba en la DLQ.
*   **Tablas.** El módulo trae una migración repetible de Flyway (`R__urbanmove_messaging.sql`) que crea las dos tablas en la base de cada servicio que lo use.
*   **Lambdas.** No usan outbox: publican directamente en SNS con el mismo contrato. Si fallan, el reintento lo hace Lambda (o la cola que las dispara).

**Consecuencias:**
*   *Positivas:* ningún evento se pierde por una caída de SNS o del servicio, y un evento duplicado no tiene efecto. Cubre cuatro de los mecanismos de tolerancia a fallos del enunciado: persistencia antes de publicar, reintentos, idempotencia y DLQ. El relay y el consumidor escalan con las réplicas del servicio. Todo se prueba con PostgreSQL y LocalStack reales (`MessagingIntegrationTest`).
*   *Negativas:* la entrega es "al menos una vez", nunca "exactamente una vez": si el servicio cae entre publicar y marcar la fila, el evento sale dos veces, y todos los consumidores tienen que ser idempotentes. Hay hasta un segundo de retraso entre el commit y la publicación. Cada cola recibe todos los eventos del topic y descarta los que no le interesan. Las tablas `outbox_events` y `processed_events` crecen sin límite.
*   *Pendiente:* purgar periódicamente las filas antiguas de las dos tablas. Valorar filtros de suscripción en SNS (por el atributo `eventType` que ya lleva cada mensaje) para que cada cola reciba solo sus eventos.
