**Estado:** propuesta. Solo cubre el endpoint de demanda, que necesita la Lambda de recolocación. Los demás endpoints de analytics (los del dashboard) se añadirán aquí.

Consultas agregadas sobre los data marts de `analytics_db`. Analytics no recibe eventos ni escribe en los data marts: las tablas las construye la Lambda de data marts a partir de `curated/` del Data Lake, y analytics solo las lee ([data-ownership.md](../data-ownership.md)). Todas las peticiones llevan `Authorization: Bearer <token_jwt>`.

**Puerto:** 8090. En Docker Compose el servicio se llama `analytics`, así que desde la red `urbanmove-net` se llega con `http://analytics:8090`.

**Formato de error:**
```json
{
  "error": "BAD_REQUEST",
  "message": "windowMinutes debe estar entre 15 y 180",
  "correlationId": "c-5d02aa31"
}
```

## 1. Demanda por zona y tipo de vehículo
**Endpoint:** `GET /api/v1/analytics/demand?windowMinutes=60` (rol `ADMIN`) \
**Descripción:** Devuelve cuántas peticiones de vehículo ha tenido cada zona, por tipo, en la ventana más reciente. Lo consulta la Lambda de recolocación (token de servicio `svc-relocation`, rol `ADMIN`) para aplicar la regla de la sección 8.3 del enunciado. También puede usarlo el dashboard. Sale del data mart de demanda.

`windowMinutes` es opcional (por defecto 60, entre 15 y 180).

**Qué cuenta como petición:**
*   Cada `VehicleReserved` de la zona (`zoneId`).
*   Cada `RideStarted` **sin reserva** (`reservationId` = `null`) de la zona de origen (`originZoneId`).

Un viaje que empieza desde una reserva no se cuenta, porque esa reserva ya se contó en `VehicleReserved`: si se sumaran los dos, un mismo usuario contaría dos veces. Las reservas rechazadas (vehículo ya reservado) no emiten evento, así que no se cuentan.

**La ventana la decide analytics.** Si el data mart agrega por horas o por cuartos de hora, la ventana se ajusta a esos tramos y puede no ser exactamente de `windowMinutes`. La respuesta dice siempre la ventana usada (`windowStart`, `windowEnd`), y la Lambda la copia tal cual en `DemandHotspotDetected`.

**Respuesta Exitosa (200 OK):**
```json
{
  "windowStart": "2026-10-13T15:00:00Z",
  "windowEnd": "2026-10-13T16:00:00Z",
  "dataUpTo": "2026-10-13T15:58:40Z",
  "zones": [
    {
      "zoneId": "zone-centro",
      "vehicleType": "SCOOTER",
      "reservations": 11,
      "ridesWithoutReservation": 3,
      "requests": 14
    },
    {
      "zoneId": "zone-norte",
      "vehicleType": "BIKE",
      "reservations": 2,
      "ridesWithoutReservation": 0,
      "requests": 2
    }
  ]
}
```
*   `requests` = `reservations` + `ridesWithoutReservation`. Es el valor que compara la Lambda y el que va en `requestsLastHour`.
*   `dataUpTo` es el momento hasta el que el data mart está procesado: contiene todos los eventos anteriores. Lo actualiza quien construye el data mart **en cada construcción, aunque no haya encontrado eventos nuevos**. Como el data mart se construye cada cierto tiempo, puede ir unos minutos por detrás. Si es `null`, el data mart no se ha construido nunca.
*   **Por qué no es el `timestamp` del último evento:** de madrugada puede pasar una hora sin reservas ni viajes. Con esa definición, `dataUpTo` dejaría de avanzar y la Lambda no podría distinguir un data mart parado de una ciudad sin demanda. Así, un `dataUpTo` reciente con pocas peticiones es poca demanda de verdad, y un `dataUpTo` viejo es que el data mart ha dejado de construirse.
*   Solo aparecen las combinaciones de zona y tipo con al menos una petición. Si no hay ninguna, `"zones": []`.
*   No incluye los vehículos disponibles: el recuento actual lo da Availability (`GET /api/v1/availability?type=`), y la Lambda lo pide allí.

**Errores:**

| Código | Cuándo |
| :--- | :--- |
| `400 Bad Request` | `windowMinutes` no es un número entre 15 y 180. |
| `401 Unauthorized` | Falta el token o no es válido. |
| `403 Forbidden` | El token no tiene rol `ADMIN`. |

**Evento emitido:** ninguno.

**Requisito en los eventos:** para contar por tipo, `VehicleReserved` y `RideStarted` llevarán un campo nuevo, `vehicleType` (`BIKE` o `SCOOTER`). Es un cambio compatible ([events.md](../events.md), regla de evolución). Los eventos anteriores al cambio no lo tienen: el data mart puede descartarlos o sacar el tipo de `VehiclePurchased` por `vehicleId`.
