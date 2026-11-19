package com.example.svcturnos.reserva.domain.exception;

public class ConfirmarHoldException extends RuntimeException {
    public enum Motivo {
        PROCESO_INEXISTENTE, PROCESO_AJENO, ESTADO_INCOMPATIBLE, HOLD_VENCIDO, HOLD_NO_ENCONTRADO,
        SOLICITUD_RECHAZADA, CREDENCIAL_TECNICA_RECHAZADA, CONFIGURACION_INCOMPLETA,
        PROCESO_YA_CONFIRMADO, RESULTADO_INCIERTO, PERSISTENCIA_FALLIDA, CORRELACION_PENDIENTE
    }
    public enum Codigo {
        HOLD_NOT_FOUND, HOLD_EXPIRED, HOLD_OWNER_MISMATCH, EXTERNAL_PATIENT_MISMATCH,
        RESERVATION_PROCESS_ALREADY_CONFIRMED, VALIDATION_ERROR, INVALID_SLOT,
        PROFESSIONAL_NOT_FOUND, SLOT_ALREADY_RESERVED, STUDENT_INTEGRATION_NOT_FOUND
    }
    private final Motivo motivo;
    private final Codigo codigo;
    private final Long procesoId;

    public ConfirmarHoldException(Motivo motivo) { this(motivo, null, null); }
    public ConfirmarHoldException(Motivo motivo, Codigo codigo, Long procesoId) {
        super("No se pudo completar la confirmación inicial: " + motivo);
        this.motivo = motivo;
        this.codigo = codigo;
        this.procesoId = procesoId;
    }
    public Motivo getMotivo() { return motivo; }
    public Codigo getCodigo() { return codigo; }
    public Long getProcesoId() { return procesoId; }
    public ConfirmarHoldException conProceso(Long id) { return new ConfirmarHoldException(motivo, codigo, id); }
}
