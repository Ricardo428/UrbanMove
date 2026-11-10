// El viaje en curso del usuario: iniciarlo, ver cuánto lleva y finalizarlo en una zona de destino.
// Solo se guarda su id: el estado se pregunta siempre a rides, que es la dueña de ese dato.
import { api, ApiError } from "./api.js";
import { onLoginChange, session } from "./session.js";
import { tabStorage } from "./storage.js";
import { formatMinutesSeconds, show, showError } from "./ui.js";

const storedRideId = tabStorage("urbanmove.ride");
// Al finalizar, availability libera el vehículo por el evento RideFinished: tarda un momento en volver a la lista.
const VEHICLE_RELEASE_WAIT_MS = 2000;
const euros = new Intl.NumberFormat("es-ES", { style: "currency", currency: "EUR" });

const panel = document.getElementById("ride-panel");
const summary = document.getElementById("ride-summary");
const elapsed = document.getElementById("ride-elapsed");
const destinationSelect = document.getElementById("ride-destination");
const finishButton = document.getElementById("ride-finish");
const result = document.getElementById("ride-result");

let current = null; // el viaje en curso, tal como lo devolvió la API
let zoneNames = new Map();
let elapsedTimer = null;
let actionInProgress = false; // evita enviar dos veces la misma acción con un doble clic

finishButton.addEventListener("click", () => runAction(finishCurrent));
onLoginChange(restoreAfterLogin, forgetEverything);

export function hasActiveRide() {
    return current !== null;
}

/**
 * Sin reservationId, el usuario coge un vehículo libre de la lista; con él, usa su reserva.
 * Devuelve true si el viaje empezó. Si falla, el error ya se muestra en el panel del viaje.
 */
export async function startRide(vehicleId, reservationId) {
    if (current) {
        show(result, "Ya tienes un viaje en curso: finalízalo primero.", true);
        return false;
    }
    const body = reservationId ? { vehicleId, reservationId } : { vehicleId };
    const started = await runAction(async () => {
        try {
            remember(await api("POST", "/api/v1/rides/start", body));
            show(result, "Viaje iniciado. Al llegar, elige la zona de destino y finalízalo.", false);
            return true;
        } finally {
            notifyChanged(); // también si falla: un 409 significa que la lista estaba desactualizada
        }
    });
    return started === true;
}

async function finishCurrent() {
    const destinationZoneId = destinationSelect.value;
    if (!destinationZoneId) {
        show(result, "Elige la zona de destino.", true);
        return;
    }
    const finished = await api("POST", "/api/v1/rides/" + current.rideId + "/finish", { destinationZoneId });
    forgetRide();
    show(result, describeFinished(finished), false);
    notifyChanged();
    setTimeout(notifyChanged, VEHICLE_RELEASE_WAIT_MS);
}

// Duración y coste los calcula rides: la web solo los muestra, no repite la tarifa.
function describeFinished(ride) {
    const text = "Viaje terminado en " + zoneName(ride.destinationZoneId) + ": " + ride.durationMinutes + " min, "
        + euros.format(ride.preliminaryCost) + " (coste provisional).";
    // zoneValidated false: zones no respondió y rides cerró el viaje igualmente (degradación funcional).
    return ride.zoneValidated ? text : text + " No se ha podido comprobar la zona de destino.";
}

// Devuelve lo que devuelva la acción, o undefined si no se ejecutó o falló.
async function runAction(action) {
    if (actionInProgress) {
        return;
    }
    actionInProgress = true;
    show(result, "", false);
    try {
        return await action();
    } catch (error) {
        showError(result, error);
        // Si la API dice que el viaje ya no está en curso (por ejemplo, se finalizó en otra pestaña), se comprueba.
        if (current && error instanceof ApiError && error.status === 409) {
            await refreshFromServer();
        }
    } finally {
        actionInProgress = false;
    }
}

async function restoreAfterLogin() {
    loadDestinations(); // independiente: si zones falla, el viaje se recupera igualmente
    const rideId = storedRideId.read();
    if (!rideId) {
        return;
    }
    current = { rideId };
    try {
        await refreshFromServer();
    } catch (error) {
        showError(result, error);
    }
}

async function loadDestinations() {
    try {
        const zones = await api("GET", "/api/v1/zones?active=true");
        if (!session.current()) {
            return; // cerró sesión mientras llegaba la respuesta
        }
        zoneNames = new Map(zones.map((zone) => [zone.zoneId, zone.name]));
        // Solo donde se puede aparcar, para no ofrecer un destino que fallará. Quien decide sigue siendo rides (422).
        const parking = zones.filter((zone) => zone.parkingAllowed);
        destinationSelect.replaceChildren(new Option("Elige una zona", ""),
            ...parking.map((zone) => new Option(zone.name, zone.zoneId)));
    } catch (error) {
        showError(result, error);
    }
}

// Pregunta a la API cómo está el viaje. Si ya no está en curso, deja de seguirse y se dice por qué.
async function refreshFromServer() {
    const rideId = current.rideId;
    let latest;
    try {
        latest = await api("GET", "/api/v1/rides/" + rideId);
    } catch (error) {
        if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
            forgetRide();
            notifyChanged();
            return;
        }
        throw error;
    }
    if (current?.rideId !== rideId) {
        return; // cerró sesión mientras llegaba la respuesta
    }
    if (latest.status === "IN_PROGRESS") {
        remember(latest);
    } else {
        forgetRide();
        show(result, "Tu viaje " + rideId + " ya no está en curso: " + latest.status + ".", false);
        setTimeout(notifyChanged, VEHICLE_RELEASE_WAIT_MS);
    }
    notifyChanged();
}

function remember(ride) {
    current = ride;
    storedRideId.write(ride.rideId);
    summary.textContent = "Viaje " + ride.rideId + " con el vehículo " + ride.vehicleId + ", desde "
        + zoneName(ride.originZoneId);
    panel.hidden = false;
    clearInterval(elapsedTimer);
    drawElapsed();
    elapsedTimer = setInterval(drawElapsed, 1000);
}

function drawElapsed() {
    const seconds = Math.max(0, Math.floor((Date.now() - new Date(current.startTime).getTime()) / 1000));
    // Solo orienta al usuario: los minutos que se cobran los cuenta rides al finalizar.
    elapsed.textContent = "Tiempo de viaje: " + formatMinutesSeconds(seconds);
}

function zoneName(zoneId) {
    return zoneNames.get(zoneId) ?? zoneId;
}

function forgetRide() {
    clearInterval(elapsedTimer);
    current = null;
    panel.hidden = true;
    storedRideId.remove();
}

function forgetEverything() {
    forgetRide();
    zoneNames = new Map();
    destinationSelect.replaceChildren(new Option("Elige una zona", ""));
    show(result, "", false);
}

function notifyChanged() {
    window.dispatchEvent(new Event("ride-changed"));
}
