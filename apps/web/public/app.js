// Conecta la página con la API: cada formulario llama a api() y muestra el resultado.
import { api } from "./api.js";
import { session } from "./session.js";
import { show, showError } from "./ui.js";
import "./vehicles.js"; // la pantalla de vehículos se gestiona sola

const loggedOut = document.getElementById("logged-out");
const loggedIn = document.getElementById("logged-in");
const whoami = document.getElementById("whoami");

const registerForm = document.getElementById("register-form");
const registerResult = document.getElementById("register-result");
const loginForm = document.getElementById("login-form");
const loginResult = document.getElementById("login-result");
const logoutButton = document.getElementById("logout");

registerForm.addEventListener("submit", async (event) => {
    event.preventDefault(); // sin esto, el navegador recargaría la página
    const email = registerForm.email.value.trim();
    const password = registerForm.password.value;

    try {
        await api("POST", "/api/v1/identity/register", { email, password });
        show(registerResult, "Cuenta creada. Ya puedes iniciar sesión con " + email + ".", false);
        registerForm.reset();
        loginForm.email.value = email;
    } catch (error) {
        showError(registerResult, error);
    }
});

loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = loginForm.email.value.trim();
    const password = loginForm.password.value;

    try {
        const login = await api("POST", "/api/v1/identity/login", { email, password });
        session.start(email, login);
        loginForm.reset();
        show(loginResult, "", false);
        await confirmSession();
    } catch (error) {
        showError(loginResult, error);
    }
});

logoutButton.addEventListener("click", () => session.end());

// Cualquier cambio de sesión (login, logout, token caducado) redibuja la página.
window.addEventListener("session-changed", render);

// Pregunta a identity si el token sigue valiendo y quién es el usuario. Si no vale, api() cierra la sesión.
async function confirmSession() {
    if (!session.current()) {
        return;
    }
    try {
        const result = await api("GET", "/api/v1/identity/validate");
        session.confirm(result.userId);
    } catch (error) {
        showError(loginResult, error);
    }
}

function render() {
    const current = session.current();
    loggedOut.hidden = current !== null;
    loggedIn.hidden = current === null;
    if (current) {
        const id = current.userId ? ", id " + current.userId : "";
        whoami.textContent = current.email + " (" + current.role + id + ")";
    }
}

// Al cargar: si la pestaña ya tenía sesión (por ejemplo, tras recargar), se comprueba que siga valiendo.
render();
confirmSession();
