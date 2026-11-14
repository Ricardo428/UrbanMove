# ADR 009: Baja de usuario entre Profiles e Identity

**Estado:** aceptada. Implementada en `services/profiles`, `services/identity` y `libs/events-contract` (evento `UserDeactivated`).

**Problema:**
Cuando un usuario se da de baja (`DELETE /api/v1/profiles/me`), Profiles marca su perfil como `INACTIVE`, pero la cuenta vive en Identity, que no se enteraba: el usuario podía seguir iniciando sesión, reservando y haciendo viajes, y lo único que perdía era la edición del perfil. Cada servicio es dueño de sus datos (`docs/data-ownership.md`), así que Profiles no puede tocar la tabla de usuarios; hay que decidir cómo se lo comunica a Identity.

**Alternativas consideradas:**
*   *Profiles llama por HTTP a Identity (con un token de servicio, ADR-006):* la baja tiene efecto inmediato, pero si Identity no responde hay que elegir entre fallar la baja o dejarla a medias (perfil inactivo, cuenta activa), que es justo el error que se quiere evitar. Además acopla a Profiles con la disponibilidad de Identity.
*   *Que cada servicio compruebe en Profiles si el usuario está de baja:* no toca a Identity, pero obliga a Reservations, Rides y los demás a consultar a Profiles en cada petición, y el login seguiría funcionando.
*   *Evento `UserDeactivated` por el bus (elegida):* Profiles lo guarda en su outbox en la misma transacción que la baja (ADR-005) e Identity lo consume.

**Decisión:**
*   **Profiles** publica `UserDeactivated` (`userId`, `deactivatedAt`) al dar de baja el perfil. La baja y el evento se guardan los dos o ninguno. Si el perfil no existe o ya estaba de baja, no se publica nada.
*   **Identity** tiene su propia cola (`urbanmove-identity` y su dead-letter queue) y un `UserDeactivatedHandler` idempotente: `UPDATE users SET enabled = false WHERE id = ? AND enabled = true`. Repetir el evento no cambia nada, y un usuario desconocido se registra en el log y se ignora, porque reintentarlo no lo haría aparecer.
*   La cuenta deshabilitada **no puede iniciar sesión**: `login` responde 403 si la contraseña es correcta (así quien no la conoce no averigua si la cuenta existe) y `GET /validate` responde 401. La fila no se borra: es una baja lógica, como en Profiles.
*   `validate` solo comprueba usuarios (`u-<id>`); los tokens de servicio (`svc-*`) no son usuarios de la tabla y se aceptan como antes.

**Consecuencias:**
*   *Positivas:* Profiles no depende de que Identity esté disponible y la baja nunca queda a medias de forma permanente. Si Identity está caída, el evento espera en la cola y se aplica al volver. Reutiliza la outbox, la idempotencia y la dead-letter queue que ya existían.
*   *Negativas:* es consistencia eventual: entre la baja y la entrega del evento pasan segundos en los que el usuario aún puede iniciar sesión.
*   *Limitación conocida:* los tokens ya emitidos siguen valiendo hasta que expiran (1 hora), porque Reservations, Rides y el resto comprueban la firma localmente sin consultar a Identity (ADR-004). Un usuario dado de baja puede seguir actuando hasta que caduque su token, pero no puede renovarlo. Acortar la vida del token o consultar a Identity en cada petición lo cerraría, a costa de más carga y de acoplar todos los servicios a Identity; no se hace de momento.
*   *Pendiente:* decidir si se debe impedir que un ADMIN se quede sin acceso al darse de baja, y si Reservations debe cancelar las reservas activas del usuario (podría consumir también `UserDeactivated`).
