-- Una base de datos lógica por microservicio (ADR-001 y docs/data-ownership.md):
-- ningún servicio consulta la base de datos de otro.
-- Postgres solo ejecuta este script la primera vez que crea el volumen.
-- Para volver a ejecutarlo: docker compose down -v

CREATE DATABASE identity_db;
CREATE DATABASE profiles_db;
CREATE DATABASE zones_db;
CREATE DATABASE inventory_db;
CREATE DATABASE availability_db;
CREATE DATABASE reservations_db;
CREATE DATABASE rides_db;
CREATE DATABASE history_db;
CREATE DATABASE maintenance_orders_db;
CREATE DATABASE relocation_orders_db;
-- Data marts: capa analítica, separada de las bases transaccionales.
CREATE DATABASE analytics_db;
