// Pantalla de vehículos disponibles. Los filtros los aplica availability, que es la dueña de ese dato.
import { api } from "./api.js";
import { hasActiveReservation, reserveVehicle } from "./reservations.js";
import { hasActiveRide, startRide } from "./rides.js";
import { onLoginChange, session } from "./session.js";
import { show, showError } from "./ui.js";

const TYPE_LABELS = { BIKE: "Bicicleta", SCOOTER: "Patinete" };

const zoneSelect = document.getElementById("vehicle-zone");
const typeSelect = document.getElementById("vehicle-type");
const vehicleList = document.getElementById("vehicle-list");
const vehiclesResult = document.getElementById("vehicles-result");
const refreshButton = document.getElementById("vehicle-refresh");

let zoneNames = new Map();
let latestVehicleRequest = 0;

zoneSelect.addEventListener("change", loadVehicles);
typeSelect.addEventListener("change", loadVehicles);
refreshButton.addEventListener("click", loadVehicles);
// Una reserva o un viaje que empieza o termina cambia qué vehículos están libres.
for (const eventName of ["reservation-changed", "ride-changed"]) {
    window.addEventListener(eventName, () => {
        if (session.current()) {
            loadVehicles();
        }
    });
}
onLoginChange(loadZonesAndVehicles, clearScreen);

async function loadZonesAndVehicles() {
    try {
        const zones = await api("GET", "/api/v1/zones?active=true");
        if (!session.current()) {
            return; // cerró sesión mientras llegaba la respuesta
        }
        showZones(zones);
    } catch (error) {
        showError(vehiclesResult, error);
        return;
    }
    await loadVehicles();
}

async function loadVehicles() {
    // Si cambia un filtro con otra petición en curso, la respuesta que cuenta es la última.
    const request = ++latestVehicleRequest;
    try {
        const vehicles = await api("GET", vehiclesPath());
        if (request === latestVehicleRequest) {
            showVehicles(vehicles);
        }
    } catch (error) {
        if (request === latestVehicleRequest) {
            vehicleList.replaceChildren();
            showError(vehiclesResult, error);
        }
    }
}

function vehiclesPath() {
    const filters = new URLSearchParams();
    if (zoneSelect.value) {
        filters.set("zoneId", zoneSelect.value);
    }
    if (typeSelect.value) {
        filters.set("type", typeSelect.value);
    }
    const query = filters.toString();
    return query ? "/api/v1/availability?" + query : "/api/v1/availability";
}

function showZones(zones) {
    zoneNames = new Map(zones.map((zone) => [zone.zoneId, zone.name]));
    const allZones = new Option("Todas las zonas", "");
    zoneSelect.replaceChildren(allZones, ...zones.map((zone) => new Option(zone.name, zone.zoneId)));
}

function showVehicles(vehicles) {
    vehicleList.replaceChildren(...vehicles.map(vehicleItem));
    show(vehiclesResult, vehicles.length === 0 ? "No hay vehículos disponibles con estos filtros." : "", false);
}

function vehicleItem(vehicle) {
    const item = document.createElement("li");
    const type = TYPE_LABELS[vehicle.vehicleType] ?? vehicle.vehicleType;
    const zone = zoneNames.get(vehicle.zoneId) ?? vehicle.zoneId;
    // Con una reserva o un viaje en curso no se ofrece coger otro: el primero seguiría bloqueando su vehículo.
    const busy = hasActiveReservation() || hasActiveRide();
    item.append(type + " " + vehicle.vehicleId + " · " + zone + " ",
        actionButton("Reservar", busy, () => reserveVehicle(vehicle.vehicleId)), " ",
        actionButton("Iniciar viaje", busy, () => startRide(vehicle.vehicleId)));
    return item;
}

function actionButton(text, disabled, onClick) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = text;
    button.disabled = disabled;
    button.addEventListener("click", onClick);
    return button;
}

function clearScreen() {
    latestVehicleRequest++; // descarta la respuesta que aún pudiera llegar
    zoneNames = new Map();
    zoneSelect.replaceChildren(new Option("Todas las zonas", ""));
    typeSelect.value = "";
    vehicleList.replaceChildren();
    show(vehiclesResult, "", false);
}
