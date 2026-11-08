// La reserva en curso del usuario: crearla, confirmarla, cancelarla y ver cuánto le queda.
// Solo se guarda su id: el estado se pregunta siempre a reservations, que es la dueña de ese dato.
import { api, ApiError } from "./api.js";
import { onLoginChange } from "./session.js";
import { show, showError } from "./ui.js";

const STORAGE_KEY = "urbanmove.reservation";
const ACTIVE_STATUSES = ["PENDING", "CONFIRMED"];
const STATUS_LABELS = { PENDING: "pendiente de confirmar", CONFIRMED: "confirmada" };
// Al cancelar, availability libera el vehículo por un evento: tarda un momento en volver a la lista.
const VEHICLE_RELEASE_WAIT_MS = 2000;
// Vencido el plazo, la Lambda de expiración la cierra en el minuto siguiente: la web pregunta hasta verlo.
const EXPIRED_RECHECK_SECONDS = 15;

const panel = document.getElementById("reservation-panel");
const summary = document.getElementById("reservation-summary");
const countdown = document.getElementById("reservation-countdown");
const confirmButton = document.getElementById("reservation-confirm");
const cancelButton = document.getElementById("reservation-cancel");
const result = document.getElementById("reservation-result");

let current = null; // la reserva activa, tal como la devolvió la API
let countdownTimer = null;
let actionInProgress = false; // evita enviar dos veces la misma acción con un doble clic

confirmButton.addEventListener("click", () => runAction(confirmCurrent));
cancelButton.addEventListener("click", () => runAction(cancelCurrent));
onLoginChange(restoreAfterLogin, forgetEverything);

export function hasActiveReservation() {
    return current !== null;
}

export function reserveVehicle(vehicleId) {
    // reservations no impide tener varias, pero esta pantalla solo sigue una: otra quedaría bloqueando su vehículo.
    if (current) {
        show(result, "Ya tienes una reserva en curso: confírmala o cancélala primero.", true);
        return;
    }
    return runAction(async () => {
        try {
            const created = await api("POST", "/api/v1/reservations", { vehicleId });
            remember(created);
            show(result, "Vehículo reservado. Confírmalo antes de que termine el plazo.", false);
        } finally {
            notifyChanged(); // también si falla: un 409 significa que la lista estaba desactualizada
        }
    });
}

async function confirmCurrent() {
    await api("POST", "/api/v1/reservations/" + current.reservationId + "/confirm");
    await refreshFromServer();
    show(result, "Reserva confirmada. Ya puedes iniciar el viaje dentro del nuevo plazo.", false);
}

async function cancelCurrent() {
    await api("POST", "/api/v1/reservations/" + current.reservationId + "/cancel");
    forgetReservation();
    show(result, "Reserva cancelada. El vehículo volverá a la lista en unos segundos.", false);
    notifyChanged();
    setTimeout(notifyChanged, VEHICLE_RELEASE_WAIT_MS);
}

async function runAction(action) {
    if (actionInProgress) {
        return;
    }
    actionInProgress = true;
    show(result, "", false);
    try {
        await action();
    } catch (error) {
        showError(result, error);
        // Si la API rechaza la acción (por ejemplo, ya caducó), lo que se ve en pantalla estaba desactualizado.
        if (current && error instanceof ApiError && error.status === 409) {
            await refreshFromServer();
        }
    } finally {
        actionInProgress = false;
    }
}

async function restoreAfterLogin() {
    const reservationId = readStoredId();
    if (!reservationId) {
        return;
    }
    current = { reservationId };
    try {
        await refreshFromServer();
    } catch (error) {
        showError(result, error);
    }
}

// Pregunta a la API cómo está la reserva. Si ya no está activa, deja de seguirse y se dice por qué.
async function refreshFromServer() {
    const reservationId = current.reservationId;
    let latest;
    try {
        latest = await api("GET", "/api/v1/reservations/" + reservationId);
    } catch (error) {
        if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
            forgetReservation();
            notifyChanged();
            return;
        }
        throw error;
    }
    if (current?.reservationId !== reservationId) {
        return; // cerró sesión mientras llegaba la respuesta
    }
    if (ACTIVE_STATUSES.includes(latest.status)) {
        remember(latest);
    } else {
        forgetReservation();
        show(result, "Tu reserva " + reservationId + " ya no está activa: " + latest.status + ".", false);
        setTimeout(notifyChanged, VEHICLE_RELEASE_WAIT_MS); // el vehículo vuelve por evento, un momento después
    }
    notifyChanged();
}

function remember(reservation) {
    current = reservation;
    writeStoredId(reservation.reservationId);
    summary.textContent = "Reserva " + reservation.reservationId + " del vehículo " + reservation.vehicleId
        + ": " + STATUS_LABELS[reservation.status];
    panel.hidden = false;
    startCountdown();
}

function startCountdown() {
    clearInterval(countdownTimer);
    drawCountdown();
    countdownTimer = setInterval(drawCountdown, 1000);
}

function drawCountdown() {
    const secondsLeft = Math.ceil((new Date(current.expiresAt).getTime() - Date.now()) / 1000);
    const expired = secondsLeft <= 0;
    // Solo orienta al usuario: quien decide si ha caducado es el servidor.
    countdown.textContent = expired ? "El plazo ha vencido." : "Plazo restante: " + formatTime(secondsLeft);
    confirmButton.hidden = current.status !== "PENDING" || expired;
    if (expired && secondsLeft % EXPIRED_RECHECK_SECONDS === 0) {
        checkIfClosedByServer();
    }
}

async function checkIfClosedByServer() {
    if (actionInProgress) {
        return;
    }
    try {
        await refreshFromServer();
    } catch {
        // Servicio caído un momento: se vuelve a preguntar en la siguiente comprobación.
    }
}

function formatTime(totalSeconds) {
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = String(totalSeconds % 60).padStart(2, "0");
    return minutes + ":" + seconds;
}

function forgetReservation() {
    clearInterval(countdownTimer);
    current = null;
    panel.hidden = true;
    removeStoredId();
}

function forgetEverything() {
    forgetReservation();
    show(result, "", false);
}

function notifyChanged() {
    window.dispatchEvent(new Event("reservation-changed"));
}

function readStoredId() {
    try {
        return sessionStorage.getItem(STORAGE_KEY);
    } catch {
        return null;
    }
}

function writeStoredId(reservationId) {
    try {
        sessionStorage.setItem(STORAGE_KEY, reservationId);
    } catch {
        // Almacenamiento bloqueado: la reserva sigue en pantalla, pero no sobrevive a recargar.
    }
}

function removeStoredId() {
    try {
        sessionStorage.removeItem(STORAGE_KEY);
    } catch {
        // Almacenamiento bloqueado: no había nada que borrar.
    }
}
