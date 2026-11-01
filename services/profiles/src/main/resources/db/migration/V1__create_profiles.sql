-- Perfiles de usuario. Profiles es su único dueño (docs/data-ownership.md).
-- user_id es el identificador de Identity (u-1001); no hay clave ajena porque es otra base de datos.
-- La baja es lógica: el perfil pasa a INACTIVE y los datos se conservan.
CREATE TABLE profiles (
    user_id    VARCHAR(64)              PRIMARY KEY,
    first_name VARCHAR(100)             NOT NULL,
    last_name  VARCHAR(100)             NOT NULL,
    phone      VARCHAR(20),
    newsletter BOOLEAN                  NOT NULL DEFAULT FALSE,
    language   VARCHAR(8),
    status     VARCHAR(16)              NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
