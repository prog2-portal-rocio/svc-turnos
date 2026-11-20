package com.example.svcturnos.reserva.domain.exception;

public class EnviarInformacionException extends RuntimeException {
    public enum Motivo { PROCESO_INEXISTENTE, PROCESO_AJENO, ESTADO_INCOMPATIBLE, SOLICITUD_NO_DISPONIBLE,
        CORRELACION_INVALIDA, PLAZO_VENCIDO, TELEFONO_INVALIDO, RESPUESTA_EXISTENTE, CONFIGURACION_INCOMPLETA, PERSISTENCIA_FALLIDA }
    private final Motivo motivo;
    public EnviarInformacionException(Motivo motivo) { super("No se pudo aceptar la información adicional: " + motivo); this.motivo = motivo; }
    public Motivo getMotivo() { return motivo; }
}
