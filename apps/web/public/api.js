// Única puerta de la web hacia la API: todas las llamadas pasan por api().
// Aquí se decide en un solo sitio cómo se envía una petición y cómo se entiende un error.
import { session } from "./session.js";

/** Error de la API con lo que la web necesita para mostrarlo y para buscarlo en los logs. */
export class ApiError extends Error {
    constructor(status, message, correlationId) {
        super(message);
        this.status = status;
        this.correlationId = correlationId;
    }
}

/**
 * Llama a la API a través del gateway (mismo origen, así que las rutas empiezan por /api/v1).
 * Devuelve el cuerpo JSON de la respuesta, o null si no tiene. Si el código no es 2xx, lanza ApiError.
 */
export async function api(method, path, body) {
    // Un id por acción del usuario: el servicio lo pone en sus logs y lo reenvía a los demás.
    const correlationId = newCorrelationId();
    const headers = { "X-Correlation-Id": correlationId };
    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
    }
    // Con sesión, cada servicio comprueba el token por su cuenta (mismo secreto, sin preguntar a identity).
    const token = session.token();
    if (token) {
        headers["Authorization"] = "Bearer " + token;
    }

    let response;
    try {
        response = await fetch(path, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch {
        // fetch solo falla así si no hay red o el gateway no responde.
        throw new ApiError(0, "No se puede conectar con el servidor", correlationId);
    }

    const data = await readJson(response);
    if (response.status === 401 && token) {
        // Enviamos token y lo rechazan: caducó o ya no vale. Se cierra la sesión.
        session.end();
        throw new ApiError(401, "Tu sesión ha caducado. Vuelve a iniciar sesión.", data?.correlationId ?? correlationId);
    }
    if (!response.ok) {
        throw new ApiError(response.status, errorMessage(response.status, data), data?.correlationId ?? correlationId);
    }
    return data;
}

// Mismo formato que acepta CorrelationIdFilter en los servicios: [A-Za-z0-9._-], hasta 64.
function newCorrelationId() {
    return "web-" + crypto.randomUUID().slice(0, 8);
}

// Los errores de los servicios son JSON (ErrorResponse), pero un 502/504 lo genera nginx en HTML.
async function readJson(response) {
    const text = await response.text();
    if (!text) {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}

function errorMessage(status, data) {
    if (data?.message) {
        return data.message;
    }
    if (status === 502 || status === 503 || status === 504) {
        return "El servicio no está disponible ahora mismo. Inténtalo en unos segundos.";
    }
    return "Error inesperado (" + status + ")";
}
