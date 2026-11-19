package com.example.svcturnos.reserva.domain.model;

public record EventoInbox(Long id, EventoReserva evento, EstadoEventoReserva estado, Long procesoLocalId,
                          Long originalId) {
    public boolean pendienteDeCorrelacion() {
        return estado == EstadoEventoReserva.RECIBIDO || estado == EstadoEventoReserva.PENDIENTE_CORRELACION;
    }
}
