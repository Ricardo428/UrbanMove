# Contrato de API REST: Servicio Identity

Este servicio gestiona la autenticación, sesiones y roles (usuarios finales, mantenimiento y administración). No gestiona datos de perfil.

## 1. Registro de Usuario
**Endpoint:** `POST /api/v1/identity/register` \
**Descripción:** Crea unas nuevas credenciales en el sistema.

**Cuerpo de la petición (Request):**
```json
{
  "email": "usuario@gmail.com",
  "password": "Contraseña",
  "role": "USER, MAINTENANCE, ADMIN"
}
```
**Respuesta Exitosa (201 Created):**

```json
{
  "id": "uuid-1234",
  "message": "Usuario registrado correctamente"
}
```

## 2. Iniciar Sesión (Login)
**Endpoint:** `POST /api/v1/identity/login` \
**Descripción:** Valida credenciales y emite un token de sesión.

Cabeceras (Headers): Authorization: Bearer <token_jwt>

**Cuerpo de la petición (Request):**
```json
{
  "email": "usuario@gmail.com",
  "password": "Contraseña"
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "token": "token",
  "role": "USER",
  "expiresIn": 3600
}
```

## 3. Validar Token (Uso interno de otros servicios)
**Endpoint:** `GET /api/v1/identity/validate` \
**Descripción:** Verifica si un token enviado por otro microservicio es válido y devuelve su rol

**Respuesta Exitosa (200 OK):**
```json
{
  "valid": true,
  "userId": "uuid-1234",
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