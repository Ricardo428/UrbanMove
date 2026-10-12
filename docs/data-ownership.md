# Propiedad de Datos (Data Ownership)

Para mantener el bajo acoplamiento, cada microservicio es dueño absoluto de su base de datos. Ningún servicio puede consultar la base de datos de otro directamente; deben comunicarse por API REST o eventos.

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