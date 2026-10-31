-- Estado operativo y zona actual de cada recurso. Availability es su única dueña (docs/states.md).
-- reservation_id y ride_id identifican quién tiene el recurso mientras está RESERVED o IN_USE.
CREATE TABLE vehicle_state (
    vehicle_id     VARCHAR(64)              PRIMARY KEY,
    vehicle_type   VARCHAR(16)              NOT NULL CHECK (vehicle_type IN ('BIKE', 'SCOOTER')),
    zone_id        VARCHAR(64)              NOT NULL,
    status         VARCHAR(32)              NOT NULL CHECK (status IN (
                       'AVAILABLE', 'RESERVED', 'IN_USE', 'LOW_BATTERY', 'WITHDRAWN',
                       'CHARGING', 'REPAIRING', 'READY_FOR_SERVICE', 'RETIRED')),
    reservation_id VARCHAR(64),
    ride_id        VARCHAR(64),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- La app de usuarios lista los disponibles por zona y tipo.
CREATE INDEX vehicle_state_zone_status ON vehicle_state (zone_id, status);
