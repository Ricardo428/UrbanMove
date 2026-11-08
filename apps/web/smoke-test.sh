#!/usr/bin/env bash
# Prueba de humo de la web a través del gateway. Crece con cada tarea de la app.
# Requiere el sistema levantado (docker compose --profile apps up -d --build) y los datos de prueba (seed.sh).
# Uso: apps/web/smoke-test.sh [url-base]   (por defecto http://localhost:8080)
set -u

BASE_URL="${1:-http://localhost:8080}"
failures=0

# check <descripción> <código esperado> <método> <ruta> [cuerpo JSON] [token]
check() {
    local description="$1" expected="$2" method="$3" path="$4" body="${5:-}" token="${6:-}"
    local args=(-s -o /dev/null -w '%{http_code}' --max-time 10 -X "$method")
    if [ -n "$body" ]; then
        args+=(-H 'Content-Type: application/json' -d "$body")
    fi
    if [ -n "$token" ]; then
        args+=(-H "Authorization: Bearer $token")
    fi
    local actual
    actual=$(curl "${args[@]}" "$BASE_URL$path")
    if [ "$actual" = "$expected" ]; then
        echo "OK    $description ($path -> $actual)"
    else
        echo "FALLO $description ($path -> $actual, se esperaba $expected)"
        failures=$((failures + 1))
    fi
}

# La web la sirve nginx.
check "la web se sirve" 200 GET "/"
check "se sirve el JavaScript" 200 GET "/app.js"
# Sin esta cabecera, el navegador reutiliza un JavaScript antiguo después de cada cambio.
if curl -sI --max-time 10 "$BASE_URL/app.js" | grep -qi '^cache-control: no-cache'; then
    echo "OK    el navegador revalida el JavaScript antes de usarlo"
else
    echo "FALLO /app.js no lleva Cache-Control: no-cache"
    failures=$((failures + 1))
fi
# Un 401 solo lo puede dar zones: prueba que el gateway reenvía la petición (un 502 sería que no llega).
check "el gateway llega a zones" 401 GET "/api/v1/zones"

# Registro (tarea 2a). Email distinto en cada ejecución para que el script se pueda repetir.
EMAIL="humo-$(date +%s)@urbanmove.test"
USER_JSON="{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
check "registro de un usuario nuevo" 201 POST "/api/v1/identity/register" "$USER_JSON"
check "registro con email repetido" 409 POST "/api/v1/identity/register" "$USER_JSON"
check "registro con contraseña corta" 400 POST "/api/v1/identity/register" \
    "{\"email\":\"corta-$EMAIL\",\"password\":\"123\"}"

# Login y token (tarea 2b).
check "login con contraseña incorrecta" 401 POST "/api/v1/identity/login" \
    "{\"email\":\"$EMAIL\",\"password\":\"incorrecta\"}"
TOKEN=$(curl -s --max-time 10 -H 'Content-Type: application/json' -d "$USER_JSON" \
    "$BASE_URL/api/v1/identity/login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
    echo "OK    login devuelve un token"
else
    echo "FALLO login no devuelve token"
    failures=$((failures + 1))
fi
check "el token vale a través del gateway" 200 GET "/api/v1/identity/validate" "" "$TOKEN"
check "validate con token falso" 401 GET "/api/v1/identity/validate" "" "no-es-un-token"
check "otro servicio acepta el token de identity" 200 GET "/api/v1/zones" "" "$TOKEN"

# Zonas y vehículos (tarea 3b), con un usuario normal. Necesita los datos de seed.sh.
# check_body <descripción> <ruta> <token> <texto que debe salir> [texto que no debe salir]
check_body() {
    local description="$1" path="$2" token="$3" present="$4" absent="${5:-}"
    local body
    body=$(curl -s --max-time 10 -H "Authorization: Bearer $token" "$BASE_URL$path")
    if [[ "$body" == *"$present"* ]] && { [ -z "$absent" ] || [[ "$body" != *"$absent"* ]]; }; then
        echo "OK    $description ($path)"
    else
        echo "FALLO $description ($path): se esperaba '$present' y no '${absent:-}'"
        failures=$((failures + 1))
    fi
}

check_body "las zonas activas incluyen las de prueba" "/api/v1/zones?active=true" "$TOKEN" '"zoneId":"zone-centro"'
check_body "el filtro de tipo solo devuelve ese tipo" "/api/v1/availability?type=BIKE" "$TOKEN" \
    '"vehicleType":"BIKE"' '"vehicleType":"SCOOTER"'
check_body "el filtro de zona solo devuelve esa zona" "/api/v1/availability?zoneId=zone-norte" "$TOKEN" \
    '"zoneId":"zone-norte"' '"zoneId":"zone-centro"'
check "availability sin token" 401 GET "/api/v1/availability"
check "availability con un tipo no válido" 400 GET "/api/v1/availability?type=PATIN" "" "$TOKEN"

# Reservas (tarea 4), sobre el primer vehículo que esté disponible.
# first_field <campo> <json>: el primer valor de ese campo de texto.
first_field() {
    printf '%s' "$2" | grep -o "\"$1\":\"[^\"]*\"" | head -n 1 | cut -d '"' -f 4
}

VEHICLE=$(first_field vehicleId "$(curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/availability")")
RESERVATION=$(first_field reservationId "$(curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "{\"vehicleId\":\"$VEHICLE\"}" "$BASE_URL/api/v1/reservations")")
if [ -n "$RESERVATION" ]; then
    echo "OK    reserva $RESERVATION creada sobre $VEHICLE"
else
    echo "FALLO no se pudo reservar '$VEHICLE'"
    failures=$((failures + 1))
fi

OTHER_JSON="{\"email\":\"otro-$EMAIL\",\"password\":\"password123\"}"
curl -s -o /dev/null --max-time 10 -H 'Content-Type: application/json' -d "$OTHER_JSON" "$BASE_URL/api/v1/identity/register"
OTHER_TOKEN=$(first_field token "$(curl -s --max-time 10 -H 'Content-Type: application/json' -d "$OTHER_JSON" "$BASE_URL/api/v1/identity/login")")

check "reservar sin token" 401 POST "/api/v1/reservations" "{\"vehicleId\":\"$VEHICLE\"}"
check "reservar un vehículo ya reservado" 409 POST "/api/v1/reservations" "{\"vehicleId\":\"$VEHICLE\"}" "$OTHER_TOKEN"
check "reservar un vehículo que no existe" 404 POST "/api/v1/reservations" '{"vehicleId":"v-9999"}' "$TOKEN"
check_body "availability lo muestra reservado" "/api/v1/availability/$VEHICLE" "$TOKEN" '"status":"RESERVED"'
check "ver la reserva de otro usuario" 403 GET "/api/v1/reservations/$RESERVATION" "" "$OTHER_TOKEN"
check "cancelar la reserva de otro usuario" 403 POST "/api/v1/reservations/$RESERVATION/cancel" "" "$OTHER_TOKEN"
check "confirmar la reserva" 200 POST "/api/v1/reservations/$RESERVATION/confirm" "" "$TOKEN"
check "confirmarla otra vez" 409 POST "/api/v1/reservations/$RESERVATION/confirm" "" "$TOKEN"
check "cancelar la reserva" 200 POST "/api/v1/reservations/$RESERVATION/cancel" "" "$TOKEN"
check "cancelarla otra vez" 409 POST "/api/v1/reservations/$RESERVATION/cancel" "" "$TOKEN"

# El vehículo se libera por el evento ReservationCancelled: hay que dejarle un momento.
released=no
for _ in $(seq 1 20); do
    if curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/availability/$VEHICLE" |
        grep -q '"status":"AVAILABLE"'; then
        released=yes
        break
    fi
    sleep 1
done
if [ "$released" = yes ]; then
    echo "OK    el evento ReservationCancelled libera el vehículo"
else
    echo "FALLO $VEHICLE sigue sin estar AVAILABLE tras cancelar"
    failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures prueba(s) fallida(s)"
    exit 1
fi
echo "Todas las pruebas pasan"
