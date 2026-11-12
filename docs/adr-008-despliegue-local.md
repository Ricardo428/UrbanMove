# ADR 008: Despliegue en local con Docker Compose y LocalStack

**Estado:** propuesta. Es como funciona hoy el proyecto (`docker-compose.yaml` e `infra/terraform`). Falta confirmar con el profesor que no hay que desplegar los microservicios en AWS (ver el pendiente de ADR-003).

**Problema:**
El sistema necesita PostgreSQL, siete microservicios, la app web y varios servicios de AWS: SNS y SQS para los eventos, S3 para el Data Lake, y Lambda con EventBridge para las tareas programadas. La sección 9 del enunciado permite desplegarlo en AWS Academy, en AWS real, en LocalStack o en un entorno local con servicios equivalentes, y pide justificar la elección en el PDF final. Hay que elegir un entorno en el que los dos miembros del equipo puedan arrancar el sistema completo, repetir las pruebas y hacer la demo.

**Alternativas consideradas:**
*   *AWS Academy:* es AWS de verdad y no cuesta dinero, pero las credenciales caducan al cerrar el lab y los recursos se paran o se borran al terminar. El estado de Terraform se desfasa y habría que recrearlo todo antes de cada prueba o de la demo.
*   *AWS real:* el entorno más fiel, pero necesita una cuenta con tarjeta, cuesta dinero (sobre todo las bases de datos y los servicios encendidos todo el día) y obliga a gestionar IAM, redes y secretos desde el principio.
*   *Local sin emular AWS (por ejemplo, RabbitMQ en lugar de SNS y SQS):* sencillo de levantar, pero el código de mensajería sería distinto del que iría a AWS y el Terraform no serviría para nada.
*   *Docker Compose con LocalStack (elegida):* todo corre en el ordenador de cada uno y LocalStack ofrece la misma API que AWS para los servicios que usamos.

**Decisión:**
*   **Docker Compose levanta todo el sistema.** `docker compose up -d` arranca PostgreSQL y LocalStack. Con `--profile apps` arrancan también los microservicios y la web (gateway nginx en `localhost:8080`).
*   **Un solo PostgreSQL con una base de datos lógica por servicio** (`infra/postgres-init.sql`). Cada servicio solo conoce su base (`docs/data-ownership.md`), así que separarlas en instancias distintas no cambiaría el código.
*   **LocalStack con versión fija** (`localstack/localstack:4.12.0`) y solo los servicios necesarios: `s3,sns,sqs,lambda,events,logs,iam,sts`. Fijar la versión evita que una actualización cambie el comportamiento entre los dos ordenadores.
*   **Terraform crea los recursos en LocalStack** con `-var-file=localstack.tfvars` (ADR-003). Es la misma definición que iría a AWS, cambiando la variable `use_localstack`.
*   **El código no sabe que está en local.** Los servicios usan el SDK de AWS y solo cambia la configuración: `AWS_ENDPOINT_URL=http://localstack:4566` y credenciales ficticias (`test`). En AWS bastaría con quitar el endpoint y usar credenciales reales.
*   **Red con nombre fijo** (`urbanmove-net`). LocalStack ejecuta cada Lambda en su propio contenedor y lo une a esa red para que pueda llamar a los servicios (ADR-007).
*   **Las pruebas usan lo mismo.** Las pruebas de integración levantan PostgreSQL con Testcontainers (ADR-004), y la de `libs/messaging` levanta también LocalStack para comprobar el camino real outbox → SNS → SQS.

**Consecuencias:**
*   *Positivas:* no cuesta dinero, no caduca y cualquiera lo arranca igual con los comandos del README. Se puede apagar un servicio o LocalStack para probar la tolerancia a fallos sin afectar a nadie. El código y el Terraform son los mismos que irían a AWS, así que el paso a la nube sería de configuración y no de reescritura.
*   *Negativas:* no es AWS de verdad. LocalStack no exige los permisos de IAM (por ejemplo, la política que deja a SNS escribir en cada cola), así que un error de permisos solo aparecería en AWS. No hay autoescalado ni alta disponibilidad: cada servicio es un único contenedor y PostgreSQL es una sola instancia. LocalStack pierde los recursos al apagarse, y hay que repetir `terraform apply` (con `-var deploy_expiration_lambda=true` para la Lambda). Las latencias y los límites no son los de AWS, así que no sirve para medir rendimiento.

**Pendiente:**
*   Confirmar con el profesor que basta con este entorno. Si hubiera que desplegar en AWS, faltaría el cómputo (ECS o similar) y las bases de datos gestionadas (RDS), además de guardar los secretos en Secrets Manager o SSM (ADR-007).
