# Contrato de API REST: Servicio Inventory

Registra las bicicletas y patinetes como activos: identificador, tipo, número de serie, fecha de compra, modelo y estado administrativo (`ACTIVE` o `RETIRED`). No gestiona si un recurso está libre o reservado: eso es de `Availability`. Todas las peticiones llevan `Authorization: Bearer <token_jwt>`.

**Formato de error:**
```json
{
  "error": "CONFLICT",
  "message": "Ya existe un recurso con el número de serie SN-98231",
  "correlationId": "c-1b77e9a0"
}
```

## 1. Registrar un Recurso
**Endpoint:** `POST /api/v1/inventory/vehicles` (roles `MAINTENANCE` y `ADMIN`) \
**Descripción:** Da de alta una bicicleta o un patinete nuevo. La `zoneId` es la zona en la que se pone en servicio por primera vez.

**Cuerpo de la petición (Request):**
```json
{
  "vehicleType": "SCOOTER",
  "serialNumber": "SN-98231",
  "model": "Xiaomi Pro 4",
  "purchaseDate": "2026-10-13",
  "zoneId": "zone-centro"
}
```
**Respuesta Exitosa (201 Created):**
```json
{
  "vehicleId": "v-0042",
  "vehicleType": "SCOOTER",
  "serialNumber": "SN-98231",
  "model": "Xiaomi Pro 4",
  "purchaseDate": "2026-10-13",
  "administrativeStatus": "ACTIVE"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Falta algún campo, `vehicleType` no es `BIKE` ni `SCOOTER`, o la fecha es posterior a hoy. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |
| `409 Conflict` | Ya existe un recurso con ese número de serie. |

**Evento emitido:** `VehiclePurchased`.

## 2. Consultar un Recurso
**Endpoint:** `GET /api/v1/inventory/vehicles/{vehicleId}` \
**Descripción:** Devuelve los datos del recurso.

**Respuesta Exitosa (200 OK):**
```json
{
  "vehicleId": "v-0042",
  "vehicleType": "SCOOTER",
  "serialNumber": "SN-98231",
  "model": "Xiaomi Pro 4",
  "purchaseDate": "2026-10-13",
  "administrativeStatus": "ACTIVE"
}
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `401 Unauthorized` | Falta el token o no es válido. |
| `404 Not Found` | El recurso no existe. |

**Evento emitido:** ninguno.

## 3. Listar Recursos
**Endpoint:** `GET /api/v1/inventory/vehicles?vehicleType=SCOOTER&administrativeStatus=ACTIVE` \
**Descripción:** Devuelve el catálogo de recursos. Los dos filtros son opcionales. Las apps de mantenimiento lo usan para consultar los activos.

**Respuesta Exitosa (200 OK):**
```json
[
  {
    "vehicleId": "v-0042",
    "vehicleType": "SCOOTER",
    "serialNumber": "SN-98231",
    "model": "Xiaomi Pro 4",
    "purchaseDate": "2026-10-13",
    "administrativeStatus": "ACTIVE"
  }
]
```

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | Un filtro tiene un valor no válido. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no es de `MAINTENANCE` ni `ADMIN`. |

**Evento emitido:** ninguno.
