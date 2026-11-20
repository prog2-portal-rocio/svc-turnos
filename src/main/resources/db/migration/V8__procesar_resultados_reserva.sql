ALTER TABLE procesos_reserva ADD COLUMN reserva_id BIGINT;
ALTER TABLE procesos_reserva ADD COLUMN resultado_ocurrido_en TIMESTAMPTZ;
ALTER TABLE procesos_reserva DROP CONSTRAINT procesos_reserva_estado_check;
ALTER TABLE procesos_reserva ADD CONSTRAINT procesos_reserva_estado_check CHECK (estado IN (
    'CREADO','HOLD_SOLICITADO','HOLD_RECHAZADO','HOLD_REGISTRADO','CONFIRMACION_SOLICITADA',
    'CONFIRMACION_INCIERTA','CONFIRMACION_RECHAZADA','ESPERANDO_TELEFONO','HOLD_VENCIDO','HOLD_NO_RECUPERABLE',
    'CONFIRMADA','CANCELADA','PROCESO_EXPIRADO','PROCESO_INVALIDO'));
ALTER TABLE procesos_reserva DROP CONSTRAINT chk_proceso_progreso;
ALTER TABLE procesos_reserva ADD CONSTRAINT chk_proceso_progreso CHECK (
    (estado IN ('CREADO','HOLD_SOLICITADO','HOLD_RECHAZADO')
        AND hold_id IS NULL AND proceso_externo_id IS NULL AND vencimiento_hold IS NULL)
    OR (estado NOT IN ('CREADO','HOLD_SOLICITADO','HOLD_RECHAZADO')
        AND hold_id IS NOT NULL AND LENGTH(TRIM(hold_id))>0 AND proceso_externo_id IS NOT NULL
        AND LENGTH(TRIM(proceso_externo_id))>0 AND vencimiento_hold IS NOT NULL));
ALTER TABLE procesos_reserva ADD CONSTRAINT chk_reserva_definitiva CHECK (
    (estado IN ('CONFIRMADA','CANCELADA') AND reserva_id IS NOT NULL AND reserva_id>0 AND resultado_ocurrido_en IS NOT NULL)
    OR (estado NOT IN ('CONFIRMADA','CANCELADA') AND reserva_id IS NULL));

ALTER TABLE procesos_reserva ADD CONSTRAINT chk_fecha_resultado CHECK (
    estado NOT IN ('CONFIRMADA','CANCELADA','PROCESO_EXPIRADO','PROCESO_INVALIDO') OR resultado_ocurrido_en IS NOT NULL);
CREATE UNIQUE INDEX uq_proceso_reserva_definitiva ON procesos_reserva(reserva_id) WHERE reserva_id IS NOT NULL;

ALTER TABLE eventos_reserva_inbox DROP CONSTRAINT eventos_reserva_inbox_estado_check;
ALTER TABLE eventos_reserva_inbox ADD CONSTRAINT eventos_reserva_inbox_estado_check CHECK (estado IN (
    'RECIBIDO','SOLICITUD_REGISTRADA','PENDIENTE_CORRELACION','CORRELACION_INCONSISTENTE','PENDIENTE_TIPO',
    'INVALIDO','DUPLICADO','EVENT_ID_EN_CONFLICTO','RESULTADO_APLICADO','RESULTADO_OBSOLETO','REQUIERE_RECONCILIACION'));

-- Se conserva cada envío histórico; sólo una respuesta habilitada por solicitud.
ALTER TABLE informacion_adicional_outbox DROP CONSTRAINT informacion_adicional_outbox_solicitud_id_key;
ALTER TABLE informacion_adicional_outbox ADD COLUMN habilitada BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE informacion_adicional_outbox ADD COLUMN resultado_inbox_id BIGINT REFERENCES eventos_reserva_inbox(id);
CREATE UNIQUE INDEX uq_outbox_solicitud_habilitada ON informacion_adicional_outbox(solicitud_id) WHERE habilitada;
