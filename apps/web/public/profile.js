// El perfil del usuario: verlo, crearlo o editarlo y darse de baja.
// Todas las rutas son /me: profiles saca el userId del token, así que nadie puede pedir el perfil de otro.
import { api, ApiError } from "./api.js";
import { onLoginChange, session } from "./session.js";
import { show, showError } from "./ui.js";

const form = document.getElementById("profile-form");
const fields = document.getElementById("profile-fields");
const statusText = document.getElementById("profile-status");
const deactivateButton = document.getElementById("profile-deactivate");
const result = document.getElementById("profile-result");

let hasProfile = false; // el mismo PUT crea o actualiza: esto solo cambia el mensaje

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    show(result, "", false);
    try {
        await api("PUT", "/api/v1/profiles/me", formBody());
        show(result, hasProfile ? "Perfil actualizado." : "Perfil creado.", false);
        hasProfile = true;
        deactivateButton.hidden = false;
    } catch (error) {
        showError(result, error);
        if (error instanceof ApiError && error.status === 403) {
            await loadProfile(); // dado de baja (quizá desde otra pestaña): se muestra como tal
        }
    }
});

deactivateButton.addEventListener("click", async () => {
    if (!confirm("¿Seguro que quieres darte de baja? El perfil ya no se podrá modificar.")) {
        return;
    }
    try {
        await api("DELETE", "/api/v1/profiles/me");
        await loadProfile();
        show(result, "Perfil dado de baja. Tus datos se conservan según la normativa.", false);
    } catch (error) {
        showError(result, error);
    }
});

onLoginChange(loadProfile, clearScreen);

async function loadProfile() {
    try {
        const profile = await api("GET", "/api/v1/profiles/me");
        if (session.current()) {
            showProfile(profile);
        }
    } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
            showNoProfile();
        } else {
            showError(result, error);
        }
    }
}

function showProfile(profile) {
    hasProfile = true;
    form.firstName.value = profile.firstName;
    form.lastName.value = profile.lastName;
    form.phone.value = profile.phone ?? "";
    form.newsletter.checked = profile.preferences?.newsletter === true;
    form.language.value = profile.preferences?.language ?? "";
    const inactive = profile.status === "INACTIVE";
    fields.disabled = inactive;
    deactivateButton.hidden = inactive;
    statusText.textContent = inactive ? "Tu perfil está dado de baja y no admite cambios." : "";
}

function showNoProfile() {
    hasProfile = false;
    form.reset();
    fields.disabled = false;
    deactivateButton.hidden = true;
    statusText.textContent = "Todavía no tienes perfil: rellénalo y guárdalo para crearlo.";
}

// Los campos vacíos van como null: profiles valida el teléfono con un patrón y "" no lo cumple.
function formBody() {
    return {
        firstName: form.firstName.value.trim(),
        lastName: form.lastName.value.trim(),
        phone: form.phone.value.trim() || null,
        preferences: { newsletter: form.newsletter.checked, language: form.language.value || null },
    };
}

function clearScreen() {
    hasProfile = false;
    form.reset();
    fields.disabled = false;
    deactivateButton.hidden = true;
    statusText.textContent = "";
    show(result, "", false);
}
