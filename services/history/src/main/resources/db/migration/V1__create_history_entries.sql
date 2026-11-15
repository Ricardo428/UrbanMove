-- Historial: una fila por evento consumido. History es su única dueña (docs/data-ownership.md).
-- Es inmutable: solo hay INSERT, nunca UPDATE ni DELETE.
-- user_id es NULL en los eventos que solo afectan a un vehículo (mantenimiento) y vehicle_id en los que no
-- tienen vehículo (orden de recolocación creada). details guarda el payload tal como llegó.
CREATE TABLE history_entries (
    event_id       UUID                     PRIMARY KEY,
    event_type     VARCHAR(64)              NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    user_id        VARCHAR(64),
    vehicle_id     VARCHAR(64),
    reservation_id VARCHAR(64),
    ride_id        VARCHAR(64),
    details        JSONB                    NOT NULL,
    recorded_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Una por consulta del contrato: GET /history/me y GET /history/vehicles/{id}, las más recientes primero.
CREATE INDEX history_entries_user ON history_entries (user_id, occurred_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX history_entries_vehicle ON history_entries (vehicle_id, occurred_at DESC) WHERE vehicle_id IS NOT NULL;
