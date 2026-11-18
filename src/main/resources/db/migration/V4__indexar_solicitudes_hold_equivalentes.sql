-- Apoya la comprobación por identidad y slot; no restringe históricos ni utiliza now().
CREATE INDEX idx_procesos_reserva_solicitud
    ON procesos_reserva(usuario_id, profesional_id, fecha, hora);
