package com.example.svcturnos.reserva.domain.exception;

public class CancelarReservaException extends RuntimeException {
    public enum Motivo {
        RESERVA_NO_ENCONTRADA, ESTADO_NO_PERMITIDO, SOLICITUD_INVALIDA, PROPIEDAD_RECHAZADA,
        CONFIGURACION_INCOMPLETA, CREDENCIAL_TECNICA_RECHAZADA, RESULTADO_INCIERTO, TIMEOUT,
        CONEXION_FALLIDA, PERSISTENCIA_FALLIDA, CORRELACION_INCONSISTENTE, VERIFICACION_INCOMPLETA
    }
    private final Motivo motivo;
    public CancelarReservaException(Motivo motivo) { super("No se pudo completar la cancelacion"); this.motivo = motivo; }
    public Motivo getMotivo() { return motivo; }
    public boolean resultadoIncierto() {
        return motivo == Motivo.RESULTADO_INCIERTO || motivo == Motivo.TIMEOUT || motivo == Motivo.PERSISTENCIA_FALLIDA || motivo == Motivo.CONEXION_FALLIDA;
    }
}
