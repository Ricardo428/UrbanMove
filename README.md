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

3. Comprueba que está lista:

   ```bash
   docker compose ps
   docker exec urbanmove-localstack awslocal sns list-topics
   docker exec urbanmove-localstack awslocal sqs list-queues
   ```

   PostgreSQL crea una base de datos por microservicio y LocalStack crea el tópico de eventos, las colas con su dead-letter queue y el bucket del Data Lake (`infra/postgres-init.sql` e `infra/init-aws.sh`).

4. Opcional: levanta también los microservicios que ya tienen esqueleto (identity en el puerto 8081 e inventory en el 8082):

   ```bash
   docker compose --profile apps up -d --build
   curl http://localhost:8081/actuator/health
   curl http://localhost:8082/actuator/health
   ```

Para empezar de cero, incluidas las bases de datos, usa `docker compose down -v`. Los scripts de inicio solo se ejecutan cuando se crea el volumen.

LocalStack está fijado en la versión 4.12.0. Las versiones más recientes exigen una cuenta y un token (`LOCALSTACK_AUTH_TOKEN`).

## Compilar y probar

```bash
mvn verify                                  # todos los módulos
mvn -pl services/identity -am verify        # un solo servicio y lo que necesita
```

El CI (`.github/workflows/ci.yml`) ejecuta `mvn verify` en cada push y pull request a `main`.

## Estructura del repositorio

| Carpeta | Contenido |
| --- | --- |
| `services/` | Un módulo Maven por microservicio, cada uno con su propia base de datos |
| `lambdas/` | Funciones Lambda (una por módulo) |
| `libs/events-contract/` | Sobre y DTO de los eventos compartidos |
| `apps/` | App de usuarios, app de mantenimiento y dashboard |
| `infra/` | Scripts de inicialización de PostgreSQL y LocalStack |
| `docs/` | Arquitectura, contratos de API y de eventos, estados y ADR |

## Documentación

- [Contratos de eventos](docs/events.md)
- [Máquina de estados del recurso](docs/states.md)
- [Propiedad de los datos](docs/data-ownership.md)
- [Contratos de API](docs/apis/)
- [ADR-001: stack tecnológico](docs/adr-001-stack.md)
- [ADR-002: reservas atómicas](docs/adr-002-reservations.md)
