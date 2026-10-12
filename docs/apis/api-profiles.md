# Contrato de API REST: Servicio Profiles

Gestiona la información personal de los usuarios y sus preferencias. Requiere que el usuario ya exista en `Identity`.

## 1. Crear o Actualizar Perfil
**Endpoint:** `PUT /api/v1/profiles/me` \
**Descripción:** Crea el perfil asociado al usuario autenticado o lo actualiza. El `userId` se extrae del token JWT.

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
**Respuesta Exitosa (200 OK):**

```json
{
  "userId": "uuid-1234",
  "status": "ACTIVE",
  "updatedAt": "2026-10-12T17:00:00Z"
}
```

## 2. Baja Lógica de Usuario
**Endpoint:** `DELETE /api/v1/profiles/me` \
**Descripción:** Marca el perfil como inactivo sin borrar los datos físicos de la base de datos.


```json
{
  "message": "Perfil desactivado correctamente. Sus datos se conservarán según la normativa."
}
```