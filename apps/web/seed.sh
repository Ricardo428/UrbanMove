#!/usr/bin/env bash
# Da de alta zonas y vehículos de prueba a través de la API, como lo haría un ADMIN.
# Se puede ejecutar varias veces: lo que ya existe responde 409 y se deja como está.
# Requiere el sistema levantado y las colas creadas (terraform apply).
# Uso: apps/web/seed.sh [url-base]   (por defecto http://localhost:8080)
set -u

BASE_URL="${1:-http://localhost:8080}"
ENV_FILE="$(dirname "$0")/../../.env"
# Un vehículo nuevo llega a availability por eventos: no aparece al instante.
MAX_WAIT_SECONDS=30

new_vehicle_ids=()

fail() {
    echo "ERROR: $1" >&2
    exit 1
}

# Lee una sola variable del .env, sin mostrarla y sin cargar el resto del fichero.
read_env_var() {
    [ -f "$ENV_FILE" ] || return 0
    sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# request <método> <ruta> [cuerpo JSON]: deja el resultado en response_status y response_body.
# El cuerpo va por la entrada estándar para que nunca aparezca en la lista de procesos.
request() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-s --max-time 10 -w '\n%{http_code}' -X "$method" -H "X-Correlation-Id: seed-$$")
    if [ -n "${token:-}" ]; then
        args+=(-H "Authorization: Bearer $token")
    fi
    local output
    if [ -n "$body" ]; then
        output=$(printf '%s' "$body" | curl "${args[@]}" -H 'Content-Type: application/json' --data-binary @- "$BASE_URL$path")
    else
        output=$(curl "${args[@]}" "$BASE_URL$path")
    fi
    response_status="${output##*$'\n'}"
    response_body="${output%$'\n'*}"
}

login_as_admin() {
    local email="${BOOTSTRAP_ADMIN_EMAIL:-$(read_env_var BOOTSTRAP_ADMIN_EMAIL)}"
    local password="${BOOTSTRAP_ADMIN_PASSWORD:-$(read_env_var BOOTSTRAP_ADMIN_PASSWORD)}"
    if [ -z "$email" ] || [ -z "$password" ]; then
        fail "faltan BOOTSTRAP_ADMIN_EMAIL y BOOTSTRAP_ADMIN_PASSWORD en el entorno o en .env"
    fi

    request POST "/api/v1/identity/login" \
        "{\"email\":\"$(json_escape "$email")\",\"password\":\"$(json_escape "$password")\"}"
    [ "$response_status" = "200" ] || fail "el login del ADMIN respondió $response_status"

    token=$(printf '%s' "$response_body" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
    [ -n "$token" ] || fail "el login del ADMIN no devolvió token"
    echo "OK    sesión de ADMIN iniciada"
}

# create_zone <id> <nombre> <permite aparcar> <lat> <lon> <radio en metros>
create_zone() {
    local id="$1" name="$2" parking="$3" lat="$4" lon="$5" radius="$6"
    request POST "/api/v1/zones" \
        "{\"zoneId\":\"$id\",\"name\":\"$name\",\"parkingAllowed\":$parking,\"center\":{\"lat\":$lat,\"lon\":$lon},\"radiusMeters\":$radius}"
    case "$response_status" in
        201) echo "OK    zona $id creada" ;;
        409) echo "OK    zona $id ya existía" ;;
        *) fail "crear la zona $id respondió $response_status" ;;
    esac
}

# register_vehicle <tipo> <número de serie> <modelo> <zona inicial>
register_vehicle() {
    local type="$1" serial="$2" model="$3" zone="$4"
    request POST "/api/v1/inventory/vehicles" \
        "{\"vehicleType\":\"$type\",\"serialNumber\":\"$serial\",\"model\":\"$model\",\"purchaseDate\":\"2026-10-01\",\"zoneId\":\"$zone\"}"
    case "$response_status" in
        201)
            local vehicle_id
            vehicle_id=$(printf '%s' "$response_body" | sed -n 's/.*"vehicleId":"\([^"]*\)".*/\1/p')
            [ -n "$vehicle_id" ] || fail "inventory no devolvió vehicleId para $serial"
            new_vehicle_ids+=("$vehicle_id")
            echo "OK    $type $serial registrado como $vehicle_id en $zone"
            ;;
        409) echo "OK    $type $serial ya existía" ;;
        *) fail "registrar $serial respondió $response_status" ;;
    esac
}

# Cada vehículo nuevo tiene que llegar a availability: inventory → outbox → SNS → SQS → availability.
wait_until_new_vehicles_are_available() {
    local vehicle_id waited
    for vehicle_id in ${new_vehicle_ids[@]+"${new_vehicle_ids[@]}"}; do
        waited=0
        request GET "/api/v1/availability/$vehicle_id"
        while [ "$response_status" != "200" ]; do
            [ "$waited" -lt "$MAX_WAIT_SECONDS" ] ||
                fail "$vehicle_id no ha llegado a availability en $MAX_WAIT_SECONDS s (¿terraform apply? ¿logs de inventory y availability?)"
            sleep 1
            waited=$((waited + 1))
            request GET "/api/v1/availability/$vehicle_id"
        done
        echo "OK    $vehicle_id ya está en availability (esperados $waited s)"
    done
}

login_as_admin

create_zone zone-centro "Centro" true 28.1235 -15.4363 800
create_zone zone-norte "Norte" true 28.1500 -15.4300 700
# Sin aparcamiento: sirve para probar el 422 al finalizar un viaje.
create_zone zone-sur "Sur" false 28.0997 -15.4134 600

register_vehicle BIKE SEED-0001 "Urbana 3" zone-centro
register_vehicle BIKE SEED-0002 "Urbana 3" zone-centro
register_vehicle SCOOTER SEED-0003 "Patinete S2" zone-centro
register_vehicle BIKE SEED-0004 "Urbana 3" zone-norte
register_vehicle SCOOTER SEED-0005 "Patinete S2" zone-norte
register_vehicle SCOOTER SEED-0006 "Patinete S2" zone-norte

wait_until_new_vehicles_are_available

echo "Datos de prueba listos"
