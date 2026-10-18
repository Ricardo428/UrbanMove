# Infraestructura con Terraform

Define el bus de eventos (un topic SNS y una cola SQS con su dead-letter queue por consumidor) y el bucket S3 del Data Lake (`raw/`, `trusted/` y `curated/`). La misma definición sirve para LocalStack y para AWS.

| Fichero | Contenido |
| :--- | :--- |
| `versions.tf` | Versión mínima de Terraform y del proveedor de AWS. |
| `providers.tf` | Proveedor de AWS. Con `use_localstack = true` apunta a LocalStack. |
| `variables.tf` | Variables y valores por defecto. |
| `main.tf` | Topic, colas, políticas, suscripciones y bucket. |
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
