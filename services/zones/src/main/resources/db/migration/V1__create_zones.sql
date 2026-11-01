-- Zonas de operación. Zones es su única dueña (docs/data-ownership.md).
-- El zoneId lo elige quien crea la zona (por ejemplo zone-centro); center y radius son metadatos simplificados.
CREATE TABLE zones (
    zone_id         VARCHAR(64)              PRIMARY KEY,
    name            VARCHAR(128)             NOT NULL,
    parking_allowed BOOLEAN                  NOT NULL DEFAULT TRUE,
    active          BOOLEAN                  NOT NULL DEFAULT TRUE,
    center_lat      DOUBLE PRECISION         NOT NULL CHECK (center_lat BETWEEN -90 AND 90),
    center_lon      DOUBLE PRECISION         NOT NULL CHECK (center_lon BETWEEN -180 AND 180),
    radius_meters   INTEGER                  NOT NULL CHECK (radius_meters > 0),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
