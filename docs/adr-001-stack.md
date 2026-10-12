# ADR 001: Elección del Stack Tecnológico Base

**Problema:**
Necesitamos definir el ecosistema de lenguajes, frameworks y bases de datos para desarrollar los 10 microservicios obligatorios asegurando la independencia, escalabilidad y facilidad de despliegue.

**Decisión:**
Elegimos **Java (v21) con Spring Boot 3 y PostgreSQL**. Cada microservicio tendrá su propia base de datos lógica en PostgreSQL separada.

**Consecuencias:**
*   *Positivas:* Spring Boot ofrece herramientas muy maduras para microservicios (Spring Data, Actuator para observabilidad). PostgreSQL garantiza transacciones ACID fiables para operaciones críticas como las reservas.
*   *Negativas:* Mayor consumo de memoria inicial por cada contenedor respecto a lenguajes como Go o Node.js, lo que obligará a limitar los recursos en el `docker-compose` local.