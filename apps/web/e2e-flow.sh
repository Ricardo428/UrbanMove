#!/usr/bin/env bash
# Flujo de usuario de extremo a extremo (sección 11.1 del enunciado), a través del gateway como la web,
# y la evidencia de lo que pasa detrás: eventos en las outbox, quién los consume y colas del Data Lake e historial.
# Cada paso lleva su propio X-Correlation-Id (e2e-<hora>-<paso>) para seguirlo por los servicios y los logs.
# Requiere el sistema levantado, terraform apply y seed.sh. Uso: apps/web/e2e-flow.sh [url-base]
set -u

BASE_URL="${1:-http://localhost:8080}"
RUN="e2e-$(date +%H%M%S)"
failures=0

ok() { echo "  OK    $1"; }
fail() { echo "  FALLO $1"; failures=$((failures + 1)); }

# call <paso> <método> <ruta> [cuerpo] [token]: deja el código en STATUS y el cuerpo en BODY.
# El cuerpo va por la entrada estándar: en Windows, un argumento con tildes llega a curl mal codificado.
call() {
    local step="$1" method="$2" path="$3" body="${4:-}" token="${5:-}"
    local args=(-s --max-time 10 -X "$method" -H "X-Correlation-Id: $RUN-$step" -w '\n%{http_code}')
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data-binary @-)
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    local response
    response=$(printf '%s' "$body" | curl "${args[@]}" "$BASE_URL$path")
    STATUS="${response##*$'\n'}"
    BODY="${response%$'\n'*}"
}

expect() {
    if [ "$STATUS" = "$1" ]; then ok "$2 ($STATUS)"; else fail "$2: $STATUS, se esperaba $1. $BODY"; fi
}

field() {
    printf '%s' "$BODY" | grep -o "\"$1\":\"[^\"]*\"" | head -n 1 | cut -d '"' -f 4
}

# sql <base de datos> <consulta>: el usuario de PostgreSQL lo pone el propio contenedor (no se lee .env).
sql() {
    docker exec -e QUERY="$2" urbanmove-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$0" -AtF " | " -c "$QUERY"' "$1"
}

queue_size() {
    docker exec urbanmove-localstack awslocal sqs get-queue-attributes \
        --queue-url "http://localhost:4566/000000000000/urbanmove-$1" \
        --attribute-names ApproximateNumberOfMessages \
        --query Attributes.ApproximateNumberOfMessages --output text 2>/dev/null
}

# wait_for <descripción> <ruta> <texto> <token>: los efectos de un evento llegan un momento después.
wait_for() {
    for _ in $(seq 1 20); do
        if curl -s --max-time 10 -H "Authorization: Bearer $4" "$BASE_URL$2" | grep -q "$3"; then
            ok "$1"
            return
        fi
        sleep 1
    done
    fail "$1: $2 no llega a contener $3"
}

DATALAKE_BEFORE=$(queue_size datalake-ingest)
HISTORY_BEFORE=$(queue_size history)

echo "1. El usuario se registra e inicia sesión (identity)"
EMAIL="e2e-$(date +%s)@urbanmove.test"
USER_JSON="{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
call 1 POST /api/v1/identity/register "$USER_JSON"
expect 201 "registro de $EMAIL"
call 2 POST /api/v1/identity/login "$USER_JSON"
expect 200 "login"
TOKEN=$(field token)

echo "2. Consulta los vehículos disponibles en una zona (zones + availability)"
call 3 GET "/api/v1/availability?zoneId=zone-centro" "" "$TOKEN"
ORIGIN=zone-centro DESTINATION=zone-norte
if [ -z "$(field vehicleId)" ]; then
    # Centro vacío por ejecuciones anteriores: se hace al revés y los vehículos vuelven.
    call 3 GET "/api/v1/availability?zoneId=zone-norte" "" "$TOKEN"
    ORIGIN=zone-norte DESTINATION=zone-centro
fi
expect 200 "vehículos disponibles en $ORIGIN"
VEHICLE=$(field vehicleId)
[ -n "$VEHICLE" ] && ok "elegido $VEHICLE" || fail "no hay vehículos disponibles (¿falta seed.sh?)"

echo "3. Reserva el vehículo (reservations → availability por API, síncrono)"
call 4 POST /api/v1/reservations "{\"vehicleId\":\"$VEHICLE\"}" "$TOKEN"
expect 201 "reserva"
RESERVATION=$(field reservationId)
call 5 POST "/api/v1/reservations/$RESERVATION/confirm" "" "$TOKEN"
expect 200 "confirmación de $RESERVATION"

echo "4. Inicia el viaje con la reserva (rides → reservations y availability por API)"
call 6 POST /api/v1/rides/start "{\"vehicleId\":\"$VEHICLE\",\"reservationId\":\"$RESERVATION\"}" "$TOKEN"
expect 201 "inicio del viaje"
RIDE=$(field rideId)
wait_for "RideStarted → reservations marca $RESERVATION como USED" \
    "/api/v1/reservations/$RESERVATION" '"status":"USED"' "$TOKEN"

echo "5. Finaliza el viaje en otra zona (rides → zones por API)"
call 7 POST "/api/v1/rides/$RIDE/finish" "{\"destinationZoneId\":\"$DESTINATION\"}" "$TOKEN"
MINUTES=$(printf '%s' "$BODY" | grep -o '"durationMinutes":[0-9]*' | cut -d: -f2)
COST=$(printf '%s' "$BODY" | grep -o '"preliminaryCost":[0-9.]*' | cut -d: -f2)
expect 200 "fin de $RIDE en $DESTINATION: $MINUTES min, $COST €"
wait_for "RideFinished → availability deja $VEHICLE libre" "/api/v1/availability/$VEHICLE" '"status":"AVAILABLE"' "$TOKEN"
wait_for "  y en la zona de destino $DESTINATION" "/api/v1/availability/$VEHICLE" "\"zoneId\":\"$DESTINATION\"" "$TOKEN"

echo "6. Eventos generados: outbox de cada productor, consumo y colas del historial y del Data Lake"
sleep 2 # deja al relay publicar lo último
# events <base> <paso> <tipo>: el evento de ese paso está en la outbox del productor y ya se publicó.
events() {
    local row
    row=$(sql "$1" "SELECT event_id, published_at IS NOT NULL FROM outbox_events
                     WHERE body ->> 'correlationId' = '$RUN-$2' AND event_type = '$3'")
    if [ "${row##* | }" = "t" ]; then
        ok "$3 en la outbox de $1 y publicado en SNS (eventId ${row%% | *})"
        EVENT_ID="${row%% | *}"
    else
        fail "$3 no está publicado desde $1: '$row'"
        EVENT_ID=""
    fi
}
# consumed <base> <tipo>: el consumidor guardó el eventId en processed_events (idempotencia).
consumed() {
    if [ -n "$EVENT_ID" ] && [ -n "$(sql "$1" "SELECT 1 FROM processed_events WHERE event_id = '$EVENT_ID'")" ]; then
        ok "  consumido por ${1%_db} (processed_events)"
    else
        fail "  $2 no aparece en processed_events de $1"
    fi
}

events identity_db 1 UserRegistered
events reservations_db 4 VehicleReserved
events rides_db 6 RideStarted
consumed reservations_db RideStarted
events rides_db 7 RideFinished
consumed availability_db RideFinished

DATALAKE_AFTER=$(queue_size datalake-ingest)
HISTORY_AFTER=$(queue_size history)
if [ -n "$DATALAKE_AFTER" ] && [ $((DATALAKE_AFTER - DATALAKE_BEFORE)) -ge 4 ]; then
    ok "cola urbanmove-datalake-ingest: $DATALAKE_BEFORE → $DATALAKE_AFTER mensajes (esperan al consumidor del Data Lake)"
else
    fail "cola urbanmove-datalake-ingest: '$DATALAKE_BEFORE' → '$DATALAKE_AFTER', se esperaban al menos 4 más"
fi
if [ -n "$HISTORY_AFTER" ] && [ $((HISTORY_AFTER - HISTORY_BEFORE)) -ge 3 ]; then
    ok "cola urbanmove-history: $HISTORY_BEFORE → $HISTORY_AFTER mensajes (esperan al servicio history)"
else
    fail "cola urbanmove-history: '$HISTORY_BEFORE' → '$HISTORY_AFTER', se esperaban al menos 3 más"
fi

echo
echo "Para seguir una petición en los logs: docker compose logs | grep $RUN-6"
if [ "$failures" -gt 0 ]; then
    echo "$failures comprobación(es) fallida(s)"
    exit 1
fi
echo "Flujo 11.1 completo"
