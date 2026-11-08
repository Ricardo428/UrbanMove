// Cómo se muestra un resultado en la página. Lo comparten todas las pantallas.
import { ApiError } from "./api.js";

// textContent y no innerHTML: el texto de la API se muestra tal cual y nunca se ejecuta como HTML.
export function show(element, text, isError) {
    element.textContent = text;
    element.classList.toggle("error", isError);
}

export function showError(element, error) {
    if (error instanceof ApiError) {
        show(element, error.message + " (id: " + error.correlationId + ")", true);
    } else {
        show(element, "Error inesperado en la página", true);
        console.error(error);
    }
}
