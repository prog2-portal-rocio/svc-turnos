package com.example.svcturnos.disponibilidad.domain.exception;

public class ConsultaOcupacionesException extends RuntimeException {
    public enum Motivo {
        SOLICITUD_INVALIDA, NO_AUTENTICADO, ACCESO_DENEGADO, NO_ENCONTRADO,
        ERROR_SERVICIO, TIEMPO_AGOTADO, NO_DISPONIBLE, RESPUESTA_INVALIDA, CONFIGURACION_INCOMPLETA
    }

    public enum Codigo {
        INVALID_DATE_RANGE, STUDENT_INTEGRATION_NOT_FOUND
    }

    private final Motivo motivo;
    private final Codigo codigo;

    public ConsultaOcupacionesException(Motivo motivo) {
        this(motivo, null);
    }

    public ConsultaOcupacionesException(Motivo motivo, Codigo codigo) {
        super("No se pudo consultar ocupaciones: " + motivo);
        this.motivo = motivo;
        this.codigo = codigo;
    }

    public Motivo getMotivo() { return motivo; }
    public Codigo getCodigo() { return codigo; }
}
