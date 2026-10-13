# Contrato de API REST: Servicio Identity

Este servicio gestiona la autenticación, sesiones y roles (usuarios finales, mantenimiento y administración). No gestiona datos de perfil. Las contraseñas se guardan con hash (BCrypt), nunca en claro.

**Validación del token:** Identity firma los JWT. Los demás servicios comprueban la firma en local con la clave de firma, que se configura por variable de entorno y nunca se sube al repositorio. El endpoint `validate` queda como respaldo, para que la caída de Identity no tire el resto del sistema.

**Formato de error** (el mismo en todos los servicios):
```json
{
  "error": "CONFLICT",
  "message": "El email ya está registrado",
  "correlationId": "c-8a41f0d2"
}
```

## 1. Registro de Usuario
**Endpoint:** `POST /api/v1/identity/register` (público) \
**Descripción:** Crea unas nuevas credenciales con el rol `USER`. El registro público nunca permite elegir el rol.

**Cuerpo de la petición (Request):**
```json
{
  "email": "usuario@gmail.com",
  "password": "Contraseña123"
}
```
**Respuesta Exitosa (201 Created):**

```json
{
  "id": "u-1001",
  "role": "USER",
  "message": "Usuario registrado correctamente"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Email con formato inválido o contraseña de menos de 8 caracteres. |
| `409 Conflict` | Ya existe un usuario con ese email. |

**Evento emitido:** `UserRegistered`.

## 2. Alta de Usuario con Rol
**Endpoint:** `POST /api/v1/identity/users` (solo `ADMIN`) \
**Descripción:** Crea un usuario de mantenimiento o de administración. Es la única forma de obtener los roles `MAINTENANCE` y `ADMIN`.

**Cabeceras (Headers):** `Authorization: Bearer <token_jwt>`

**Cuerpo de la petición (Request):**
```json
{
  "email": "tecnico@urbanmove.es",
  "password": "Contraseña123",
  "role": "MAINTENANCE"
}
```
**Respuesta Exitosa (201 Created):**
```json
{
  "id": "u-2001",
  "role": "MAINTENANCE",
  "message": "Usuario creado correctamente"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Email inválido, contraseña débil o `role` distinto de `USER`, `MAINTENANCE` o `ADMIN`. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un `ADMIN`. |
| `409 Conflict` | Ya existe un usuario con ese email. |

**Evento emitido:** `UserRegistered`.

## 3. Iniciar Sesión (Login)
**Endpoint:** `POST /api/v1/identity/login` (público) \
**Descripción:** Valida credenciales y emite un token de sesión.

**Cuerpo de la petición (Request):**
```json
{
  "email": "usuario@gmail.com",
  "password": "Contraseña123"
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "token": "<token_jwt>",
  "role": "USER",
  "expiresIn": 3600
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta el email o la contraseña. |
| `401 Unauthorized` | Credenciales incorrectas. |

**Evento emitido:** ninguno.

## 4. Validar Token (Uso interno de otros servicios)
**Endpoint:** `GET /api/v1/identity/validate` \
**Descripción:** Verifica si un token enviado por otro microservicio es válido y devuelve su rol.

**Cabeceras (Headers):** `Authorization: Bearer <token_jwt>`

**Respuesta Exitosa (200 OK):**
```json
{
  "valid": true,
  "userId": "u-1001",
  "role": "USER"
}
```
**Respuesta Fallida (401 Unauthorized):**
```json
{
  "valid": false,
  "error": "Token expirado o inválido"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta la cabecera `Authorization`. |
| `401 Unauthorized` | El token ha expirado, tiene la firma incorrecta o está mal formado. |

**Evento emitido:** ninguno.
