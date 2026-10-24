# ADR 004: Módulo común, JWT con clave compartida y pruebas con Testcontainers

**Estado:** aceptada. Se aplica desde la fase 1.

**Problema:**
Todos los servicios repiten tres piezas: validar el JWT que firma identity, devolver errores con el mismo formato y propagar `X-Correlation-Id`. Copiarlas en cada servicio dejaría siete copias que mantener. Además, la reserva atómica de availability (ADR-002) solo se puede probar contra una base de datos real.

**Alternativas consideradas:**
*   *Copiar el código en cada servicio:* sin dependencias entre módulos, pero cualquier cambio hay que repetirlo en todos y es fácil que diverjan.
*   *Par de claves (identity firma con la privada, los demás validan con la pública):* es lo más correcto, pero obliga a distribuir y rotar claves y no aporta nada en este proyecto.
*   *H2 en memoria para las pruebas:* rápido y sin Docker, pero no ejecuta la sentencia condicional real de PostgreSQL ni sus restricciones.

**Decisión:**
*   **Módulo Maven `libs/common`.** Lleva la validación del JWT, el formato de error y el filtro de `X-Correlation-Id`. Se activa solo en cualquier servicio que lo tenga como dependencia (autoconfiguración de Spring Boot). Cada servicio declara sus rutas públicas con `urbanmove.security.public-paths`.
*   **JWT firmado con HS256 y un secreto compartido.** El secreto llega por la variable de entorno `URBANMOVE_JWT_SECRET`, con un mínimo de 32 caracteres. El servicio no arranca si falta o es demasiado corto.
*   **Primer administrador por variables de entorno.** Identity crea un `ADMIN` inicial si existen `BOOTSTRAP_ADMIN_EMAIL` y `BOOTSTRAP_ADMIN_PASSWORD`.
*   **Pruebas de integración con Testcontainers y PostgreSQL 16.** Se necesita Docker en local y en el CI. Los tests de unidad (por ejemplo, los del JWT) no lo necesitan.

**Consecuencias:**
*   *Positivas:* un cambio en el formato de error o en la validación del token se hace en un solo sitio. Las pruebas comprueban el comportamiento real de la base de datos.
*   *Negativas:* los servicios quedan acoplados a la versión de `common`, y un cambio incompatible rompe a todos a la vez. Quien tenga el secreto puede firmar tokens de cualquier rol, así que en AWS Academy debe ser distinto del de desarrollo y no subirse nunca al repositorio. Las pruebas son más lentas que con H2.
