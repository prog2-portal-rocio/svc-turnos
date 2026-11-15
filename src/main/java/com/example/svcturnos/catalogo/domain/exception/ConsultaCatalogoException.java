package com.example.svcturnos.catalogo.domain.exception;

public class ConsultaCatalogoException extends RuntimeException {

    public enum Motivo {
        SOLICITUD_INVALIDA,
        NO_AUTENTICADO,
        ACCESO_DENEGADO,
        PROFESIONAL_NO_ENCONTRADO,
        ERROR_SERVICIO,
        TIEMPO_AGOTADO,
        NO_DISPONIBLE,
        RESPUESTA_INVALIDA
    }

    private final Motivo motivo;

    public ConsultaCatalogoException(Motivo motivo) {
        this(motivo, null);
    }

    public ConsultaCatalogoException(Motivo motivo, Throwable causa) {
        super("No se pudo consultar Catalogo: " + motivo, causa);
        this.motivo = motivo;
    }

    public Motivo getMotivo() {
        return motivo;
    }
}
