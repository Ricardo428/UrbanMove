// Un dato guardado en sessionStorage (dura lo que la pestaña) sin que un almacenamiento bloqueado rompa la página.
// Si el navegador lo bloquea, la página sigue funcionando, pero el dato no sobrevive a recargar.
export function tabStorage(key) {
    return {
        read() {
            try {
                return sessionStorage.getItem(key);
            } catch {
                return null;
            }
        },
        write(value) {
            try {
                sessionStorage.setItem(key, value);
            } catch {
                // Almacenamiento bloqueado: el dato sigue en memoria mientras no se recargue.
            }
        },
        remove() {
            try {
                sessionStorage.removeItem(key);
            } catch {
                // Almacenamiento bloqueado: no había nada que borrar.
            }
        },
    };
}
