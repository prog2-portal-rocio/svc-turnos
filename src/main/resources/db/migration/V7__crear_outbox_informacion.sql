-- El teléfono sólo se conserva mientras la publicación puede recuperarse.
CREATE TABLE informacion_adicional_outbox (
    event_id UUID PRIMARY KEY,
    proceso_id BIGINT NOT NULL REFERENCES procesos_reserva(id),
    solicitud_id BIGINT NOT NULL UNIQUE REFERENCES eventos_reserva_inbox(id),
    group_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    proceso_externo_id TEXT NOT NULL,
    hold_id TEXT NOT NULL,
    request_event_id TEXT NOT NULL,
    telefono VARCHAR(16),
    ocurrido_en TIMESTAMPTZ NOT NULL,
    vence_en TIMESTAMPTZ NOT NULL,
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('PENDIENTE','PUBLICANDO','INCIERTA','ENVIADA','VENCIDA')),
    intentos INTEGER NOT NULL DEFAULT 0 CHECK (intentos >= 0),
    proximo_intento TIMESTAMPTZ NOT NULL,
    intento_id UUID,
    concesion_hasta TIMESTAMPTZ,
    CHECK (telefono IS NULL OR telefono ~ '^\+?[0-9]{7,15}$'),
    CHECK ((estado='PUBLICANDO' AND intento_id IS NOT NULL AND concesion_hasta IS NOT NULL)
        OR (estado<>'PUBLICANDO' AND intento_id IS NULL AND concesion_hasta IS NULL)),
    CHECK (estado NOT IN ('ENVIADA','VENCIDA') OR telefono IS NULL)
);
CREATE INDEX idx_informacion_outbox_pendiente ON informacion_adicional_outbox(estado,proximo_intento);
