// La sesión del usuario en esta pestaña: el token de identity y lo que la web muestra de él.
// sessionStorage: dura lo que la pestaña. Al cerrarla, el token desaparece.
// Cada cambio avisa con el evento "session-changed" para que la página se redibuje (Observer).

const KEY = "urbanmove.session";

export const session = {
    /** Guarda el resultado del login. expiresIn llega en segundos (LoginResponse). */
    start(email, login) {
        const data = { email, token: login.token, role: login.role, expiresAt: Date.now() + login.expiresIn * 1000 };
        write(data);
    },

    /** Añade el id de usuario que confirma identity al validar el token. */
    confirm(userId) {
        const data = read();
        if (data) {
            write({ ...data, userId });
        }
    },

    /** La sesión actual, o null si no hay o el token ya caducó. */
    current() {
        const data = read();
        if (data && Date.now() >= data.expiresAt) {
            this.end();
            return null;
        }
        return data;
    },

    token() {
        return this.current()?.token ?? null;
    },

    end() {
        try {
            sessionStorage.removeItem(KEY);
        } catch {
            // Almacenamiento bloqueado: no había nada que borrar.
        }
        notify();
    },
};

function read() {
    try {
        return JSON.parse(sessionStorage.getItem(KEY));
    } catch {
        return null;
    }
}

function write(data) {
    try {
        sessionStorage.setItem(KEY, JSON.stringify(data));
    } catch {
        // Almacenamiento bloqueado (modo privado estricto): la sesión no sobrevive a recargar.
    }
    notify();
}

function notify() {
    window.dispatchEvent(new Event("session-changed"));
}
