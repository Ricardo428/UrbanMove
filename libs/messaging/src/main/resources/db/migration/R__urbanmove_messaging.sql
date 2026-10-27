-- Tablas de libs/messaging. Flyway la ejecuta en cada servicio que dependa del módulo, después de sus
-- migraciones versionadas. Es repetible: se vuelve a lanzar si cambia este fichero, por eso todo lleva
-- IF NOT EXISTS. Un cambio de columnas en una tabla ya creada necesitaría su propia migración.

-- Outbox: el evento se guarda en la misma transacción que el cambio de estado y OutboxRelay lo publica después.
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id        UUID PRIMARY KEY,
    event_type      VARCHAR(64)              NOT NULL,
    body            JSONB                    NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    attempts        INT                      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_error      VARCHAR(1000),
    published_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS outbox_events_pending
    ON outbox_events (next_attempt_at) WHERE published_at IS NULL;

-- Eventos ya procesados por este servicio: un eventId repetido se descarta.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(64)              NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
