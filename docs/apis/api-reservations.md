# Contrato de API REST: Servicio Reservations

Gestiona el ciclo de vida temporal de una reserva de un vehículo.

## 1. Crear Reserva
**Endpoint:** `POST /api/v1/reservations` \
**Descripción:** Intenta reservar un vehículo disponible. El servicio debe verificar con `Availability` que el vehículo está libre.

**Cuerpo de la petición (Request):**
```json
{
  "vehicleId": "patineteId"
}
```
**Respuesta Exitosa (201 Created):**
```json
{
  "reservationId": "resId",
  "vehicleId": "patineteId",
  "status": "PENDING",
  "expiresAt": "2026-10-12T17:15:00Z" 
}
```

## 2. Cancelar Reserva
**Endpoint:** `POST /api/v1/reservations/{reservationId}/cancel` \
**Descripción:** El usuario cancela voluntariamente su reserva antes de que expire.

**Respuesta Exitosa (200 OK):**

```json
{
  "reservationId": "resId",
  "status": "CANCELLED",
  "message": "Reserva cancelada. El vehículo vuelve a estar disponible."
}
```