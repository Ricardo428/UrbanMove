# ADR 001: Elección del Stack Tecnológico Base

**Problema:**
Necesitamos definir el ecosistema de lenguajes, frameworks y bases de datos para desarrollar los 10 microservicios obligatorios asegurando la independencia, escalabilidad y facilidad de despliegue.

**Alternativas consideradas:**
*   *Node.js con Express y MongoDB:* arranque rápido y poco consumo de memoria, pero la consistencia fuerte que necesitan las reservas exige más trabajo con una base de documentos, y el profesor pide un proyecto Maven.
*   *Python con FastAPI y PostgreSQL:* muy productivo, pero no encaja con el proyecto Maven multimódulo que pide el profesor y obligaría a mantener dos cadenas de build (servicios y Lambdas).
*   *Go con PostgreSQL:* contenedores muy ligeros, pero obligaría a abandonar Maven y a mantener un build distinto del resto del proyecto.
*   *Java con Spring Boot y PostgreSQL (elegida):* encaja con el proyecto Maven que pide el profesor, ofrece Spring Data y Actuator, y permite escribir también las Lambdas en Java con el mismo build.

**Decisión:**
Elegimos **Java (v21) con Spring Boot 3 y PostgreSQL**, organizado como un **proyecto Maven multimódulo**: un `pom.xml` raíz y un módulo por microservicio. Cada microservicio tendrá su propia base de datos lógica en PostgreSQL separada. Los eventos viajan por SNS y SQS, que en local se ejecutan sobre LocalStack.
**Consecuencias:**
*   *Positivas:* Spring Boot ofrece herramientas muy maduras para microservicios (Spring Data, Actuator para observabilidad). PostgreSQL garantiza transacciones ACID fiables para operaciones críticas como las reservas. Un único mvn verify compila y prueba todos los módulos, también en el CI.
*   *Negativas:* Mayor consumo de memoria inicial por cada contenedor respecto a lenguajes como Go o Node.js, lo que obligará a limitar los recursos en el `docker-compose` local. Los módulos comparten el pom raíz, así que un cambio de versión afecta a todos.