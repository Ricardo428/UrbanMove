# Contrato de API REST: Servicio Zones

Mantiene las zonas permitidas, sus restricciones de aparcamiento y unos metadatos geográficos simplificados (un centro y un radio). Todas las peticiones llevan `Authorization: Bearer <token_jwt>`.

**Formato de error:**
```json
{
  "error": "NOT_FOUND",
  "message": "La zona zone-sur no existe",
  "correlationId": "c-9e3340b7"
}
```

## 1. Listar Zonas
**Endpoint:** `GET /api/v1/zones` \
**Descripción:** Devuelve las zonas de operación. Admite el filtro opcional `?active=true`.

**Respuesta Exitosa (200 OK):**
```json
[
  {
    "zoneId": "zone-centro",
    "name": "Centro",
    "parkingAllowed": true,
    "active": true
  },
  {
    "zoneId": "zone-norte",
    "name": "Norte",
    "parkingAllowed": true,
    "active": true
  }
]
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | El valor de `active` no es `true` ni `false`. |
| `401 Unauthorized` | Falta el token o no es válido. |

**Evento emitido:** ninguno.

## 2. Consultar Zona
**Endpoint:** `GET /api/v1/zones/{zoneId}` \
**Descripción:** Devuelve una zona con sus metadatos. Rides la usa al finalizar un viaje para comprobar que se puede aparcar en el destino.

**Respuesta Exitosa (200 OK):**
```json
{
  "zoneId": "zone-centro",
  "name": "Centro",
  "parkingAllowed": true,
  "active": true,
  "center": { "lat": 28.1235, "lon": -15.4363 },
  "radiusMeters": 800
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `404 Not Found` | La zona no existe. |

**Evento emitido:** ninguno.

## 3. Crear Zona
**Endpoint:** `POST /api/v1/zones` (solo `ADMIN`) \
**Descripción:** Da de alta una zona de operación.

**Cuerpo de la petición (Request):**
```json
{
  "zoneId": "zone-sur",
  "name": "Sur",
  "parkingAllowed": true,
  "center": { "lat": 28.0997, "lon": -15.4134 },
  "radiusMeters": 600
}
```
**Respuesta Exitosa (201 Created):**
```json
{
  "zoneId": "zone-sur",
  "name": "Sur",
  "parkingAllowed": true,
  "active": true,
  "center": { "lat": 28.0997, "lon": -15.4134 },
  "radiusMeters": 600
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta `zoneId` o `name`, o el radio no es positivo. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un `ADMIN`. |
| `409 Conflict` | Ya existe una zona con ese `zoneId`. |

**Evento emitido:** ninguno.

## 4. Actualizar Zona
**Endpoint:** `PUT /api/v1/zones/{zoneId}` (solo `ADMIN`) \
**Descripción:** Cambia las restricciones o los metadatos de una zona, o la desactiva con `"active": false`. Una zona desactivada deja de aceptar nuevos aparcamientos, pero los vehículos que ya están en ella se conservan.

**Cuerpo de la petición (Request):**
```json
{
  "name": "Sur",
  "parkingAllowed": false,
  "active": true,
  "center": { "lat": 28.0997, "lon": -15.4134 },
  "radiusMeters": 600
}
```
**Respuesta Exitosa (200 OK):**
```json
{
  "zoneId": "zone-sur",
  "name": "Sur",
  "parkingAllowed": false,
  "active": true,
  "center": { "lat": 28.0997, "lon": -15.4134 },
  "radiusMeters": 600
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Faltan campos o el radio no es positivo. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de un `ADMIN`. |
| `404 Not Found` | La zona no existe. |

**Evento emitido:** ninguno.
