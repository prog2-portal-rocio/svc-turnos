package com.example.svcturnos.reserva.domain.model;

/** El JSON válido se conserva completo; los bytes originales se conservan incluso si no se pueden interpretar. */
public record RecepcionEventoReserva(MensajeReserva mensaje, EventoReserva evento, String json, MotivoInvalido invalidez) {
    public enum MotivoInvalido { JSON_INVALIDO, DATOS_INVALIDOS, ESQUEMA_NO_SOPORTADO, CLAVE_INCONSISTENTE }

    public RecepcionEventoReserva {
        if (mensaje == null || ((evento == null) != (invalidez != null)) || (evento != null && json == null)) {
            throw new IllegalArgumentException("Recepción inconsistente");
        }
    }
}
