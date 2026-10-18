# ADR 003: Terraform como única definición de la infraestructura de AWS

**Estado:** aceptada. Ya está implementada en `infra/terraform`.

**Problema:**
El sistema usa recursos de AWS (un topic SNS, una cola SQS con su dead-letter queue por consumidor y el bucket S3 del Data Lake, y más adelante las Lambdas). Hay que crearlos de forma repetible en dos sitios: en local, sobre LocalStack, y en AWS Academy. El profesor pide usar Terraform. Al empezar, esos recursos se creaban con un script de shell (`infra/init-aws.sh`) que solo funcionaba en LocalStack, y mantenerlo a la vez que el Terraform habría dejado la misma infraestructura descrita dos veces.

**Alternativas consideradas:**
*   *Script con `awslocal` (la versión inicial):* sencillo y se ejecuta solo al arrancar LocalStack, pero no sirve para AWS Academy y no se puede revisar un plan antes de aplicar cambios.
*   *Script con AWS CLI:* serviría para AWS, pero obliga a escribir a mano la lógica de "crear si no existe" y no guarda el estado de lo creado.
*   *CloudFormation:* nativo de AWS, pero solo sirve para AWS y el profesor pide Terraform.
*   *Terraform (elegida):* una sola definición que se aplica a LocalStack y a AWS, con `terraform plan` para ver los cambios antes de aplicarlos y `terraform destroy` para limpiar.

**Decisión:**
*   **Terraform es la única definición de la infraestructura.** Vive en `infra/terraform/`. Se elimina `infra/init-aws.sh` y su montaje en `docker-compose.yaml`: el compose solo levanta PostgreSQL y LocalStack, y el topic, las colas y el bucket los crea Terraform.
*   **Un interruptor para elegir destino.** La variable `use_localstack` vale `false` por defecto, para que un `terraform apply` sin más vaya a AWS real y no a local por error. En local se usa `-var-file=localstack.tfvars`, que activa los endpoints de LocalStack y las credenciales ficticias.
*   **Región `us-east-1` por defecto**, la misma que el compose. AWS Academy suele limitar la región a `us-east-1` o `us-west-2`.
*   **Una cola por consumidor.** La variable `consumers` lista los consumidores de eventos, tal y como figuran en [events.md](events.md). Añadir un consumidor nuevo es añadir un nombre a esa lista.
*   **Política en cada cola.** En AWS real, SNS solo puede escribir en una cola si esta lo permite, así que cada cola lleva una política que autoriza al topic. LocalStack no lo exige, pero sin ella los eventos no llegarían en AWS.
*   **Nombre del bucket.** En local es `urbanmove-datalake`. En AWS se le añade el id de la cuenta, porque los nombres de bucket son únicos en todo AWS.
*   **Credenciales de AWS Academy por variables de entorno** (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` y `AWS_SESSION_TOKEN`), nunca en los ficheros.
*   **Qué se sube a Git.** Los `.tf`, `localstack.tfvars` y `.terraform.lock.hcl`. No se suben `.terraform/`, `*.tfstate*` ni otros `*.tfvars`, porque el estado puede contener datos de la cuenta.
*   **CI.** Un job comprueba `terraform fmt -check`, `init` y `validate` en cada push y pull request.

**Consecuencias:**
*   *Positivas:* la infraestructura se puede revisar en el código, se crea igual en local y en AWS, y se puede destruir y recrear con un comando. El `plan` evita sorpresas al aplicar. El CI detecta errores de formato o de sintaxis antes de llegar a `main`.
*   *Negativas:* hace falta tener Terraform instalado y recordar ejecutar `terraform apply` tras `docker compose up -d`, y de nuevo tras un `docker compose down -v`, porque LocalStack pierde los recursos y el estado local de Terraform deja de coincidir. El estado es local a cada persona, así que si los dos aplican contra AWS Academy cada uno tendrá un estado distinto: conviene que lo aplique una sola persona. Las credenciales de Academy caducan y los recursos pueden pararse o borrarse al terminar el lab, lo que desfasa el estado.

**Pendiente:**
*   Decidir con el profesor el alcance: si basta con definir la infraestructura de mensajería y datos, o también hay que desplegar los microservicios en AWS (cómputo y base de datos gestionada), lo que añadiría módulos de Terraform.
*   Añadir las Lambdas cuando existan, usando el rol que ofrece AWS Academy en lugar de crear uno propio.
*   Valorar un estado remoto compartido si se llega a desplegar de forma habitual en AWS.