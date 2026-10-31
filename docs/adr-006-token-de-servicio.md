# ADR 006: Token de servicio para las llamadas internas

**Estado:** aceptada. Implementada en reservations (`AvailabilityClient`); rides la usará para `start`.

**Problema:**
Reservations y Rides llaman de forma síncrona a Availability (`POST .../reserve` y `.../start`, ver [ADR-002](adr-002-reservations.md)). Esos endpoints exigen el rol `ADMIN` para que un usuario no pueda reservar un vehículo saltándose Reservations. Hace falta decidir con qué token se autentica un servicio cuando llama a otro.

**Alternativas consideradas:**
*   *Reenviar el token del usuario:* no necesita nada nuevo, pero Availability tendría que aceptar el rol `USER` en `reserve` y `start`, y entonces cualquier usuario podría llamarlos directamente.
*   *Cuenta de servicio en Identity:* cada servicio hace login con un email y una contraseña propios que llegan por variables de entorno. Añade credenciales que gestionar y hace que no se pueda reservar si Identity está caído.
*   *El servicio firma su propio token (elegida):* el servicio genera un token con rol `ADMIN` y un identificador propio (`svc-reservations`) usando el mismo secreto JWT con el que ya valida los tokens de los usuarios ([ADR-004](adr-004-modulo-comun-jwt-pruebas.md)).

**Decisión:**
*   Cada llamada interna lleva un token recién firmado con `JwtTokenService.generate("svc-<servicio>", Role.ADMIN)`. No se guarda ni se reutiliza.
*   El identificador `svc-<servicio>` aparece como `userId` en los logs del servicio llamado, así que se distingue quién hizo la llamada.
*   La llamada propaga el `X-Correlation-Id` de la petición original y tiene un timeout explícito (3 s en reservations).

**Consecuencias:**
*   *Positivas:* no hay credenciales nuevas ni dependencia de Identity en el camino de la reserva. Es una sola línea en el cliente y se prueba sin infraestructura.
*   *Negativas:* cualquier servicio que conozca el secreto puede emitir tokens `ADMIN`. Ya ocurría con HS256 y un secreto compartido; el secreto nunca está en el repositorio y llega por variable de entorno. Si se necesitara separar permisos, el paso siguiente sería un par de claves (RS256) con la clave privada solo en Identity.
