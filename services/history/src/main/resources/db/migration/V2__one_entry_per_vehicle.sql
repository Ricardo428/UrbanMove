-- Un evento puede dar varias entradas: RelocationOrderCompleted guarda una por cada vehículo recolocado
-- (docs/apis/api-history.md), para que aparezca en GET /history/vehicles/{id} de cada uno.
-- La clave deja de ser event_id y pasa a ser un id propio de la fila.
ALTER TABLE history_entries DROP CONSTRAINT history_entries_pkey;
ALTER TABLE history_entries ADD COLUMN id BIGSERIAL PRIMARY KEY;

-- Sigue siendo la red de seguridad contra duplicados: el mismo evento no puede guardar dos veces la misma
-- entrada. NULLS NOT DISTINCT trata dos vehicle_id NULL como iguales (por defecto serían distintos y no se
-- detectaría el duplicado de un evento sin vehículo, como RelocationOrderCreated).
CREATE UNIQUE INDEX history_entries_event_vehicle
    ON history_entries (event_id, vehicle_id) NULLS NOT DISTINCT;
