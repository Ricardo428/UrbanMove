#!/usr/bin/env bash
# Prueba de humo de la web a través del gateway. Crece con cada tarea de la app.
# Requiere el sistema levantado: docker compose --profile apps up -d --build
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

if [ "$failures" -gt 0 ]; then
    echo "$failures prueba(s) fallida(s)"
    exit 1
fi
echo "Todas las pruebas pasan"
