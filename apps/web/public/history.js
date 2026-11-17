// Mi historial: lo que el usuario ha hecho (reservas y viajes), tal como lo guarda history.
// History solo se entera por eventos, así que lo último tarda unos segundos en aparecer (consistencia eventual).
import { api } from "./api.js";
import { onLoginChange, session } from "./session.js";
import { show, showError } from "./ui.js";

// Tiempo que se deja al evento para ir de la outbox a la tabla de history antes de recargar.
const EVENT_ARRIVAL_WAIT_MS = 3000;

// Los tipos que history consume hoy. Uno nuevo se muestra con su nombre técnico en vez de romper la lista.
const TYPE_LABELS = {
    VehicleReserved: "Reserva creada",
    ReservationCancelled: "Reserva cancelada",
    ReservationExpired: "Reserva caducada",
    RideStarted: "Viaje iniciado",
    RideFinished: "Viaje finalizado",
};

const list = document.getElementById("history-list");
const result = document.getElementById("history-result");
const refreshButton = document.getElementById("history-refresh");

let latestRequest = 0;
let pendingReload = null;

refreshButton.addEventListener("click", loadHistory);
// Cada aviso reinicia la espera: si llegan varios seguidos, se recarga una sola vez, 3 s después del último.
for (const eventName of ["reservation-changed", "ride-changed"]) {
    window.addEventListener(eventName, () => {
        clearTimeout(pendingReload);
        pendingReload = setTimeout(() => {
            if (session.current()) {
                loadHistory();
            }
        }, EVENT_ARRIVAL_WAIT_MS);
    });
}
onLoginChange(loadHistory, clearScreen);

async function loadHistory() {
    // Si se pide otra vez antes de que llegue la respuesta, solo cuenta la última.
    const request = ++latestRequest;
    try {
        const history = await api("GET", "/api/v1/history/me");
        if (request === latestRequest) {
            showEntries(history.entries);
        }
    } catch (error) {
        if (request === latestRequest) {
            list.replaceChildren();
            showError(result, error); // si history está caído, solo falla esta sección
        }
    }
}

// History ya las devuelve ordenadas por cuándo ocurrieron (occurredAt), la más reciente primero.
function showEntries(entries) {
    list.replaceChildren(...entries.map(entryItem));
    show(result, entries.length === 0 ? "Todavía no tienes actividad." : "", false);
}

function entryItem(entry) {
    const parts = [
        new Date(entry.occurredAt).toLocaleString("es-ES", { dateStyle: "short", timeStyle: "short" }),
        TYPE_LABELS[entry.type] ?? entry.type,
        "vehículo " + entry.vehicleId,
    ];
    if (entry.type === "RideFinished") {
        parts.push(entry.details.durationMinutes + " min", formatEuros(entry.details.preliminaryCost));
    }
    const item = document.createElement("li");
    item.textContent = parts.join(" · ");
    return item;
}

function formatEuros(amount) {
    return Number(amount).toLocaleString("es-ES", { style: "currency", currency: "EUR" });
}

function clearScreen() {
    latestRequest++; // descarta la respuesta que aún pudiera llegar
    clearTimeout(pendingReload);
    list.replaceChildren();
    show(result, "", false);
}
