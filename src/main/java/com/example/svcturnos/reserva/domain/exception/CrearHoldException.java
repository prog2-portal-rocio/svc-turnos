package com.example.svcturnos.reserva.domain.exception;

public class CrearHoldException extends RuntimeException {
    public enum Motivo {
        NO_AUTENTICADO, NO_AUTORIZADO, SLOT_NO_DISPONIBLE, CONFIGURACION_INCOMPLETA,
        SOLICITUD_INVALIDA, CREDENCIAL_TECNICA_RECHAZADA, PROFESIONAL_INEXISTENTE,
        CONFLICTO, SOLICITUD_DUPLICADA, RESULTADO_INCIERTO, PERSISTENCIA_FALLIDA
    }

    public enum Codigo {
        VALIDATION_ERROR, STUDENT_INTEGRATION_NOT_FOUND, PROFESSIONAL_NOT_FOUND,
        PROFESSIONAL_DISABLED, INVALID_SLOT, SLOT_ALREADY_RESERVED, SLOT_ALREADY_HELD
    }

    private final Motivo motivo;
    private final Codigo codigo;
    private final Long procesoId;

    public CrearHoldException(Motivo motivo) { this(motivo, null, null); }

    public CrearHoldException(Motivo motivo, Codigo codigo, Long procesoId) {
        super("No se pudo completar la solicitud de hold: " + motivo);
        this.motivo = motivo;
        this.codigo = codigo;
        this.procesoId = procesoId;
    }

    public CrearHoldException conProceso(Long id) { return new CrearHoldException(motivo, codigo, id); }
    public Motivo getMotivo() { return motivo; }
    public Codigo getCodigo() { return codigo; }
    public Long getProcesoId() { return procesoId; }
}
