package com.example.svcturnos.reserva.domain.exception;

public class ConsultaReservasException extends RuntimeException {
    public enum Motivo {
        PROCESO_NO_ENCONTRADO, CONFIGURACION_INCOMPLETA, SOLICITUD_RECHAZADA,
        CREDENCIAL_TECNICA_RECHAZADA, SERVICIO_NO_DISPONIBLE, TIMEOUT, CONEXION_FALLIDA,
        RESPUESTA_INVALIDA
    }
    private final Motivo motivo;
    public ConsultaReservasException(Motivo motivo) {
        super("No se pudo completar la consulta de reservas");
        this.motivo = motivo;
    }
    public Motivo getMotivo() { return motivo; }
}
