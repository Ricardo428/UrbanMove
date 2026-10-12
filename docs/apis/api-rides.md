# Contrato de API REST: Servicio Rides

Gestiona el uso efectivo de los recursos.

## 1. Iniciar Viaje
**Endpoint:** `POST /api/v1/rides/start` \
**Descripción:** Inicia el uso de un recurso, ya sea desde una reserva previa o directamente escaneando un vehículo disponible.

**Cuerpo de la petición (Request):**
```json
{
  "vehicleId": "patineteId",
  "reservationId": "resID"
}
```

**Respuesta Exitosa (201 Created):**

```json
{
  "rideId": "rideId",
  "vehicleId": "patineteId",
  "startTime": "2026-10-12T17:10:00Z",
  "status": "IN_PROGRESS"
}
```

## 2. Finalizar Viaje
**Endpoint:** `POST /api/v1/rides/{rideId}/finish` \
**Descripción:** Termina el trayecto. El usuario debe indicar la zona de destino para comprobar si es un área permitida.

**Cuerpo de la petición (Request):**

```json
{
  "destinationZoneId": "zona-centro-01"
}
```

**Respuesta Exitosa (200 OK):**

```json
{
  "rideId": "rideId",
  "endTime": "2026-10-12T17:40:00Z",
  "durationMinutes": 30,
  "originZoneId": "zona-norte-03",
  "destinationZoneId": "zona-centro-01",
  "preliminaryCost": 4.50,
  "status": "COMPLETED"
}
```