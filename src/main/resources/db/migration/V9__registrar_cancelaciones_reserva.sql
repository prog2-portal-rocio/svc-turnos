CREATE TABLE cancelaciones_reserva (
    proceso_externo_id VARCHAR(255) PRIMARY KEY CHECK (LENGTH(TRIM(proceso_externo_id)) > 0),
    usuario_id BIGINT NOT NULL REFERENCES usuarios(id),
    reserva_id BIGINT NOT NULL UNIQUE CHECK (reserva_id > 0),
    estado VARCHAR(20) NOT NULL CHECK (estado IN ('SOLICITADA','INCIERTA','RECHAZADA','CANCELADA')),
    solicitada_en TIMESTAMPTZ NOT NULL,
    actualizada_en TIMESTAMPTZ NOT NULL,
    resultado_en TIMESTAMPTZ,
    requiere_reconciliacion BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (actualizada_en >= solicitada_en),
    CHECK (estado = 'CANCELADA' OR (resultado_en IS NULL AND NOT requiere_reconciliacion))
);
