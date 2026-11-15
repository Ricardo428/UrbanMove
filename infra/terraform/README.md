# Infraestructura con Terraform

Define el bus de eventos (un topic SNS y una cola SQS con su dead-letter queue por consumidor) y el bucket S3 del Data Lake (`raw/`, `trusted/` y `curated/`). La misma definición sirve para LocalStack y para AWS.

| Fichero | Contenido |
| :--- | :--- |
| `versions.tf` | Versión mínima de Terraform y del proveedor de AWS. |
| `providers.tf` | Proveedor de AWS. Con `use_localstack = true` apunta a LocalStack. |
| `variables.tf` | Variables y valores por defecto. |
| `main.tf` | Topic, colas, políticas, suscripciones y bucket. |
| `lambdas.tf` | Lambda de expiración de reservas y su programación en EventBridge (solo con `deploy_expiration_lambda = true`), y Lambda de ingesta al Data Lake con su conexión a la cola (solo con `deploy_ingest_lambda = true`). |
| `outputs.tf` | ARN del topic, URLs de colas y nombre del bucket. |
| `localstack.tfvars` | Valores para el uso local. |

## En local (LocalStack)

Con `docker compose up -d` ya en marcha y `localstack` en estado `healthy`:

```
cd infra/terraform
terraform init
terraform apply -var-file=localstack.tfvars
```

Para empezar de cero, `terraform destroy -var-file=localstack.tfvars`. Si has hecho `docker compose down -v`, borra también `terraform.tfstate` de esta carpeta, porque el estado ya no coincidirá con LocalStack.

Para comprobarlo: `terraform output`.

### Lambda de expiración (opcional)

La Lambda caduca las reservas vencidas cada minuto (ver [ADR-007](../../docs/adr-007-lambda-expiracion.md)). No se crea por defecto, porque necesita el jar compilado y el secreto JWT. Desde la raíz del repositorio:

```
./mvnw package -pl lambdas/expiration -am -DskipTests
docker compose --profile apps up -d --build
cd infra/terraform
terraform apply -var-file=localstack.tfvars -var deploy_expiration_lambda=true -var jwt_secret="$JWT_SECRET"
```

En PowerShell, `$env:TF_VAR_jwt_secret = "<el mismo valor que JWT_SECRET del .env>"` y el mismo `terraform apply` sin `-var jwt_secret`. El secreto no se escribe en ningún fichero del repositorio. Para ver que funciona:

```
docker exec urbanmove-localstack awslocal lambda list-functions
docker logs urbanmove-reservations --since 3m | grep lambda-expiration
```

Si reservations no está arrancada, la Lambda falla y lo reintenta al minuto siguiente.

### Lambda de ingesta al Data Lake (opcional)

La Lambda copia cada evento de la cola `urbanmove-datalake-ingest` a la capa `raw/` del bucket (ver [ADR-010](../../docs/adr-010-ingesta-data-lake.md)). No necesita secretos. Desde la raíz del repositorio:

```
./mvnw package -pl lambdas/ingestion -am -DskipTests
cd infra/terraform
terraform apply -var-file=localstack.tfvars -var deploy_ingest_lambda=true
```

Se pueden desplegar las dos Lambdas a la vez con ambas variables. Para comprobarlo, genera algún evento (registra un usuario, por ejemplo) y mira el bucket:

```
docker exec urbanmove-localstack awslocal s3 ls s3://urbanmove-datalake/raw/ --recursive
```

Verás una clave como `raw/event_date=2026-11-14/event_type=UserRegistered/source=identity-service/<eventId>.json`. Los mensajes que la Lambda no puede guardar acaban, tras 3 intentos, en la cola `urbanmove-datalake-ingest-dlq`.

Los eventos que ya estaban en la cola antes de crear la Lambda se procesan al desplegarla.

## En AWS Academy

1. Inicia el lab y copia las credenciales de *AWS Details* (incluido el `session token`) en variables de entorno de tu terminal:
   ```
   # PowerShell
   $env:AWS_ACCESS_KEY_ID="..."
   $env:AWS_SECRET_ACCESS_KEY="..."
   $env:AWS_SESSION_TOKEN="..."
   ```
2. Ejecuta:
   ```
   cd infra/terraform
   terraform init
   terraform plan
   terraform apply
   ```
   Sin `-var-file`, `use_localstack` vale `false` y se usa AWS real, en `us-east-1` por defecto. Si tu lab usa otra región, añade `-var aws_region=us-west-2`.
3. Las credenciales caducan al terminar la sesión del lab, y los recursos pueden pararse o borrarse. Al empezar de nuevo, `terraform plan` te dirá qué falta.
4. Al terminar, `terraform destroy`.

## Qué se sube a Git y qué no

- Se sube: los `.tf`, `localstack.tfvars` y `.terraform.lock.hcl` (lo genera `terraform init`; sirve para que todos usen la misma versión del proveedor).
- No se sube: `.terraform/`, `*.tfstate*` y cualquier `*.tfvars` con datos propios. Están en el `.gitignore`.

Antes de hacer commit: `terraform fmt -recursive` y `terraform validate`.
