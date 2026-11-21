package com.example.svcturnos.reserva.domain.model;

import java.util.Optional;

public enum TipoResultadoReserva {
    CONFIRMACION("AppointmentConfirmed"), RECHAZO_TELEFONO("AdditionalInformationRejected"),
    EXPIRACION("AppointmentProcessExpired"), INVALIDEZ("AppointmentProcessInvalid"), CANCELACION("AppointmentCancelled");
    private final String evento;
    TipoResultadoReserva(String evento) { this.evento = evento; }
    public String evento() { return evento; }
    public static Optional<TipoResultadoReserva> deEvento(String evento) {
        for (var tipo : values()) { if (tipo.evento.equals(evento)) { return Optional.of(tipo); } }
        return Optional.empty();
    }
}
