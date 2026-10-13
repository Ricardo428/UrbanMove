# Contrato de API REST: Servicio Profiles

Gestiona la información personal de los usuarios y sus preferencias. Requiere que el usuario ya exista en `Identity`. Todas las peticiones llevan `Authorization: Bearer <token_jwt>` y el `userId` se extrae del token, de modo que cada usuario solo accede a su propio perfil.

**Formato de error:**
```json
{
  "error": "NOT_FOUND",
  "message": "El usuario todavía no tiene perfil",
  "correlationId": "c-8a41f0d2"
}
```

## 1. Crear o Actualizar Perfil
**Endpoint:** `PUT /api/v1/profiles/me` \
**Descripción:** Crea el perfil asociado al usuario autenticado o lo actualiza.

**Cuerpo de la petición (Request):**
```json
{
  "firstName": "Ana",
  "lastName": "García",
  "phone": "+34600123456",
  "preferences": {
    "newsletter": true,
    "language": "es"
  }
}
```
**Respuesta Exitosa (201 Created si se crea, 200 OK si se actualiza):**

```json
{
  "userId": "u-1001",
  "status": "ACTIVE",
  "updatedAt": "2026-10-13T09:10:00Z"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `firstName` o `lastName`, o el teléfono tiene un formato inválido. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El perfil está dado de baja y no admite cambios. |

**Evento emitido:** ninguno. El alta del usuario ya la anuncia Identity con `UserRegistered`.

## 2. Consultar Perfil
**Endpoint:** `GET /api/v1/profiles/me` \
**Descripción:** Devuelve el perfil del usuario autenticado.

**Respuesta Exitosa (200 OK):**
```json
{
  "userId": "u-1001",
  "firstName": "Ana",
  "lastName": "García",
  "phone": "+34600123456",
  "preferences": {
    "newsletter": true,
    "language": "es"
  },
  "status": "ACTIVE"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `404 Not Found` | El usuario todavía no ha creado su perfil. |

**Evento emitido:** ninguno.

## 3. Baja Lógica de Usuario
**Endpoint:** `DELETE /api/v1/profiles/me` \
**Descripción:** Marca el perfil como inactivo sin borrar los datos físicos de la base de datos.

**Respuesta Exitosa (200 OK):**
```json
{
  "message": "Perfil desactivado correctamente. Sus datos se conservarán según la normativa."
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `404 Not Found` | El usuario no tiene perfil. |
| `409 Conflict` | El perfil ya estaba dado de baja. |

**Evento emitido:** ninguno.
