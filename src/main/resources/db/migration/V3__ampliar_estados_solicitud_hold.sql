-- No cambia datos ni V2: conserva CREADO/HOLD_REGISTRADO y admite progreso previo al POST.
ALTER TABLE procesos_reserva DROP CONSTRAINT procesos_reserva_estado_check;
ALTER TABLE procesos_reserva ADD CONSTRAINT procesos_reserva_estado_check
    CHECK (estado IN ('CREADO', 'HOLD_SOLICITADO', 'HOLD_RECHAZADO', 'HOLD_REGISTRADO'));
ALTER TABLE procesos_reserva DROP CONSTRAINT chk_proceso_progreso;
ALTER TABLE procesos_reserva ADD CONSTRAINT chk_proceso_progreso CHECK (
    (estado IN ('CREADO', 'HOLD_SOLICITADO', 'HOLD_RECHAZADO')
        AND hold_id IS NULL AND proceso_externo_id IS NULL AND vencimiento_hold IS NULL)
    OR (estado = 'HOLD_REGISTRADO' AND hold_id IS NOT NULL AND LENGTH(TRIM(hold_id)) > 0
        AND proceso_externo_id IS NOT NULL AND LENGTH(TRIM(proceso_externo_id)) > 0
        AND vencimiento_hold IS NOT NULL)
);
