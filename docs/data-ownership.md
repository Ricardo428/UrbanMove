# Propiedad de Datos (Data Ownership)

Para mantener el bajo acoplamiento, cada microservicio es dueño absoluto de su base de datos. Ningún servicio puede consultar la base de datos de otro directamente; deben comunicarse por API REST o eventos. En local, todas las bases de datos viven en un mismo PostgreSQL pero son independientes (infra/postgres-init.sql).

| Entidad Principal | Microservicio Dueño | Descripción del Dominio |
| :--- | :--- | :--- |
| **Usuarios y Roles** | `identity` | Credenciales, tokens JWT y roles de sistema. |
| **Perfiles** | `profiles` | Datos personales de los usuarios. |
| **Zonas** | `zones` | Áreas de operación y restricciones geográficas. |
| **Recursos Físicos** | `inventory` | El catálogo de bicicletas/patinetes, fecha de compra y modelo. |
| **Estado Operativo** | `availability` | Si un recurso está libre, reservado, etc., en tiempo real. |
| **Reservas** | `reservations` | Ciclo de vida de una reserva (creada, confirmada, expirada). |
| **Viajes** | `rides` | Duración del trayecto, zonas origen/destino y coste. |
| **Histórico** | `history` | Registro inmutable de todo lo que ocurre. |
| **Órdenes Mantenimiento** | `maintenance-orders`| Recargas, reparaciones y averías. |
| **Órdenes Recolocación** | `relocation-orders`| Redistribución de flota. |
| **Data Marts** | `analytics` | Tablas agregadas de flota, reservas, viajes y demanda (base `analytics_db`). Las construyen las Lambdas y `analytics` las consulta. |
| **Data Lake** | Lambda de ingesta | Bucket S3 `urbanmove-datalake` con las capas `raw/`, `trusted/` y `curated/`. Ningún microservicio lo usa como base de datos transaccional. |
| **Eventos pendientes y procesados** | Cada servicio, en su propia base | Tabla outbox con los eventos aún por publicar y tabla con los `eventId` ya consumidos. |
