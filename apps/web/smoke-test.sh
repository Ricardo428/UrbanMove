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
        # Por la entrada estándar y no como argumento: en Windows, un argumento con tildes llega a curl
        # en otra codificación, no es UTF-8 válido y el servicio responde 400.
        args+=(-H 'Content-Type: application/json' --data-binary @-)
    fi
    if [ -n "$token" ]; then
        args+=(-H "Authorization: Bearer $token")
    fi
    local actual
    actual=$(printf '%s' "$body" | curl "${args[@]}" "$BASE_URL$path")
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

# wait_available <evento>: el vehículo se libera por un evento, así que hay que dejarle un momento.
wait_available() {
    for _ in $(seq 1 20); do
        if curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/availability/$VEHICLE" |
            grep -q '"status":"AVAILABLE"'; then
            echo "OK    el evento $1 libera el vehículo"
            return
        fi
        sleep 1
    done
    echo "FALLO $VEHICLE sigue sin estar AVAILABLE tras $1"
    failures=$((failures + 1))
}

wait_available ReservationCancelled

# Viaje sin reserva (tarea 5a), con el mismo vehículo, ya libre otra vez.
VEHICLE_JSON="{\"vehicleId\":\"$VEHICLE\"}"
STARTED=$(curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$VEHICLE_JSON" "$BASE_URL/api/v1/rides/start")
RIDE=$(first_field rideId "$STARTED")
ORIGIN=$(first_field originZoneId "$STARTED")
if [ -n "$RIDE" ]; then
    echo "OK    viaje $RIDE iniciado con $VEHICLE desde $ORIGIN"
else
    echo "FALLO no se pudo iniciar un viaje con '$VEHICLE'"
    failures=$((failures + 1))
fi
check_body "availability lo muestra en uso" "/api/v1/availability/$VEHICLE" "$TOKEN" '"status":"IN_USE"'
check "iniciar un viaje con un vehículo en uso" 409 POST "/api/v1/rides/start" "$VEHICLE_JSON" "$OTHER_TOKEN"
check "iniciar un viaje con un vehículo que no existe" 404 POST "/api/v1/rides/start" '{"vehicleId":"v-9999"}' "$TOKEN"
check "iniciar un viaje sin vehículo" 400 POST "/api/v1/rides/start" '{}' "$TOKEN"
check_body "el viaje está en curso" "/api/v1/rides/$RIDE" "$TOKEN" '"status":"IN_PROGRESS"'
check "ver el viaje de otro usuario" 403 GET "/api/v1/rides/$RIDE" "" "$OTHER_TOKEN"

FINISH="/api/v1/rides/$RIDE/finish"
# Se finaliza en la zona de origen para que los datos de seed.sh no cambien de sitio en cada ejecución.
ORIGIN_JSON="{\"destinationZoneId\":\"$ORIGIN\"}"
check "finalizar el viaje de otro usuario" 403 POST "$FINISH" "$ORIGIN_JSON" "$OTHER_TOKEN"
check "finalizar en una zona sin aparcamiento" 422 POST "$FINISH" '{"destinationZoneId":"zone-sur"}' "$TOKEN"
check "finalizar en una zona que no existe" 404 POST "$FINISH" '{"destinationZoneId":"zone-nada"}' "$TOKEN"
check "finalizar el viaje" 200 POST "$FINISH" "$ORIGIN_JSON" "$TOKEN"
check "finalizarlo otra vez" 409 POST "$FINISH" "$ORIGIN_JSON" "$TOKEN"
wait_available RideFinished

# Viaje desde una reserva (tarea 5b), con el mismo vehículo.
RESERVATION=$(first_field reservationId "$(curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$VEHICLE_JSON" "$BASE_URL/api/v1/reservations")")
WITH_RESERVATION="{\"vehicleId\":\"$VEHICLE\",\"reservationId\":\"$RESERVATION\"}"
check "iniciar con la reserva de otro usuario" 403 POST "/api/v1/rides/start" "$WITH_RESERVATION" "$OTHER_TOKEN"
# Sin esta comprobación (isFor), la reserva de un vehículo serviría para coger otro y el primero quedaría bloqueado.
check "iniciar con la reserva en otro vehículo" 409 POST "/api/v1/rides/start" \
    "{\"vehicleId\":\"v-9999\",\"reservationId\":\"$RESERVATION\"}" "$TOKEN"
check "iniciar con una reserva que no existe" 404 POST "/api/v1/rides/start" \
    "{\"vehicleId\":\"$VEHICLE\",\"reservationId\":\"res-1\"}" "$TOKEN"
STARTED=$(curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$WITH_RESERVATION" "$BASE_URL/api/v1/rides/start")
RIDE=$(first_field rideId "$STARTED")
if [ -n "$RIDE" ]; then
    echo "OK    viaje $RIDE iniciado con la reserva $RESERVATION"
else
    echo "FALLO no se pudo iniciar un viaje con la reserva '$RESERVATION': $STARTED"
    failures=$((failures + 1))
fi

# La reserva pasa a USED por el evento RideStarted, un momento después.
used=no
for _ in $(seq 1 20); do
    if curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" "$BASE_URL/api/v1/reservations/$RESERVATION" |
        grep -q '"status":"USED"'; then
        used=yes
        break
    fi
    sleep 1
done
if [ "$used" = yes ]; then
    echo "OK    el evento RideStarted marca la reserva como USED"
else
    echo "FALLO la reserva $RESERVATION no pasa a USED"
    failures=$((failures + 1))
fi
check "una reserva usada no vale para otro viaje" 409 POST "/api/v1/rides/start" "$WITH_RESERVATION" "$TOKEN"
check "cancelar una reserva usada" 409 POST "/api/v1/reservations/$RESERVATION/cancel" "" "$TOKEN"
check "finalizar el viaje de la reserva" 200 POST "/api/v1/rides/$RIDE/finish" "$ORIGIN_JSON" "$TOKEN"
wait_available RideFinished

# Perfil (tarea 6). El usuario de esta ejecución es nuevo: todavía no tiene perfil.
PROFILE="/api/v1/profiles/me"
PROFILE_JSON='{"firstName":"Ana","lastName":"García","phone":"+34600123456","preferences":{"newsletter":true,"language":"es"}}'
check "perfil sin crear" 404 GET "$PROFILE" "" "$TOKEN"
check "baja sin perfil" 404 DELETE "$PROFILE" "" "$TOKEN"
check "perfil sin token" 401 GET "$PROFILE"
check "perfil con teléfono no válido" 400 PUT "$PROFILE" '{"firstName":"Ana","lastName":"García","phone":"12"}' "$TOKEN"
check "perfil sin nombre" 400 PUT "$PROFILE" '{"lastName":"García"}' "$TOKEN"
check "crear el perfil" 201 PUT "$PROFILE" "$PROFILE_JSON" "$TOKEN"
check "actualizar el perfil" 200 PUT "$PROFILE" '{"firstName":"Ana María","lastName":"García"}' "$TOKEN"
check_body "el perfil guarda los cambios" "$PROFILE" "$TOKEN" '"firstName":"Ana María"'
# /me sale del token: el otro usuario ve el suyo (que no existe), nunca el de Ana.
check "otro usuario no ve ese perfil" 404 GET "$PROFILE" "" "$OTHER_TOKEN"
check "darse de baja" 200 DELETE "$PROFILE" "" "$TOKEN"
check "darse de baja otra vez" 409 DELETE "$PROFILE" "" "$TOKEN"
check "modificar un perfil dado de baja" 403 PUT "$PROFILE" "$PROFILE_JSON" "$TOKEN"
check_body "tras la baja el perfil está INACTIVE" "$PROFILE" "$TOKEN" '"status":"INACTIVE"'
check_body "la baja es lógica: los datos siguen" "$PROFILE" "$TOKEN" '"firstName":"Ana María"'

if [ "$failures" -gt 0 ]; then
    echo "$failures prueba(s) fallida(s)"
    exit 1
fi
echo "Todas las pruebas pasan"
