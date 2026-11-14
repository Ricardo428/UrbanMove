-- Baja de usuario: la pide profiles con el evento UserDeactivated. Una cuenta deshabilitada no puede iniciar sesión.
ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
