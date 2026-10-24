# Urban Move

Sistema distribuido de movilidad urbana (bicicletas y patinetes eléctricos). Proyecto final de Computación en la Nube, Grado en Ingeniería Informática, ULPGC.

## Requisitos

- Docker con Docker Compose
- Java 21
- Maven 3.9 o superior

## Cómo ejecutar en local

1. Copia `.env.example` a `.env`. Los valores por defecto sirven para desarrollo local.

   ```bash
   cp .env.example .env
   ```

2. Levanta la infraestructura (PostgreSQL y LocalStack):

   ```bash
   docker compose up -d
   ```

3. Crea el bus de eventos y el Data Lake en LocalStack con Terraform (hace falta tener Terraform instalado):

   ```bash
   cd infra/terraform
   terraform init
   terraform apply -var-file=localstack.tfvars
   cd ../..
   ```

   PostgreSQL crea una base de datos por microservicio al arrancar (`infra/postgres-init.sql`). El topic, las colas con su dead-letter queue y el bucket los crea Terraform (ver [infra/terraform/README.md](infra/terraform/README.md), que explica también cómo usarlo en AWS Academy).

4. Comprueba que todo está listo:

   ```bash
   docker compose ps
   terraform -chdir=infra/terraform output
   docker exec urbanmove-localstack awslocal sqs list-queues
   ```

5. Opcional: levanta también los microservicios que ya existen (identity en el puerto 8081 e inventory en el 8082):

   ```bash
   docker compose --profile apps up -d --build
   curl http://localhost:8081/actuator/health
   curl http://localhost:8082/actuator/health
   ```

   Identity necesita `JWT_SECRET` (al menos 32 caracteres) en el `.env`, que ya trae un valor de desarrollo en `.env.example`. Si defines también `BOOTSTRAP_ADMIN_EMAIL` y `BOOTSTRAP_ADMIN_PASSWORD`, identity crea ese usuario ADMIN al arrancar, y con él se dan de alta los usuarios de mantenimiento y otros administradores.

   Prueba rápida del flujo de identity:

   ```bash
   curl -X POST http://localhost:8081/api/v1/identity/register \
     -H 'Content-Type: application/json' \
     -d '{"email":"usuario@example.com","password":"Contrasena123"}'
   curl -X POST http://localhost:8081/api/v1/identity/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"usuario@example.com","password":"Contrasena123"}'
   ```

Para empezar de cero, incluidas las bases de datos, usa `docker compose down -v`. El script de PostgreSQL solo se ejecuta cuando se crea el volumen. Tras un `down -v`, borra también `infra/terraform/terraform.tfstate` y vuelve a ejecutar `terraform apply`, porque LocalStack habrá perdido los recursos.

LocalStack está fijado en la versión 4.12.0. Las versiones más recientes exigen una cuenta y un token (`LOCALSTACK_AUTH_TOKEN`).
## Compilar y probar

```bash
mvn verify                                  # todos los módulos
mvn -pl services/identity -am verify        # un solo servicio y lo que necesita
```

Las pruebas de los servicios con base de datos usan Testcontainers, así que necesitan Docker en marcha (también en el CI). Para arrancar un servicio fuera de Docker, exporta antes `URBANMOVE_JWT_SECRET` con la misma clave del `.env`.

El CI (`.github/workflows/ci.yml`) ejecuta `mvn verify` y comprueba el formato y la validez del Terraform en cada push y pull request a main.

## Estructura del repositorio

| Carpeta | Contenido |
| --- | --- |
| `services/` | Un módulo Maven por microservicio, cada uno con su propia base de datos |
| `lambdas/` | Funciones Lambda (una por módulo) |
| `libs/common/` | Código compartido por los servicios: validación del JWT, formato de error y `X-Correlation-Id` |
| `libs/events-contract/` | Sobre y DTO de los eventos compartidos (aún sin código) |
| `apps/` | App de usuarios, app de mantenimiento y dashboard |
| `infra/` | Script de inicialización de PostgreSQL y terraform/ con la infraestructura de AWS (SNS, SQS y S3) |
| `docs/` | Arquitectura, contratos de API y de eventos, estados y ADR |

## Documentación

- [Contratos de eventos](docs/events.md)
- [Máquina de estados del recurso](docs/states.md)
- [Propiedad de los datos](docs/data-ownership.md)
- [Contratos de API](docs/apis/)
- [ADR-001: stack tecnológico](docs/adr-001-stack.md)
- [ADR-002: reservas atómicas](docs/adr-002-reservations.md)
- [ADR-003: Terraform para la infraestructura](docs/adr-003-terraform.md)
- [ADR-004: módulo común, JWT y pruebas con Testcontainers](docs/adr-004-modulo-comun-jwt-pruebas.md)