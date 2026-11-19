package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.model.EventoInbox;
import com.example.svcturnos.reserva.domain.model.EstadoEventoReserva;
import com.example.svcturnos.reserva.domain.model.RecepcionEventoReserva;
import com.example.svcturnos.reserva.domain.ports.in.RecibirEventoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.in.CorrelacionarSolicitudInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.InboxReservasPort;
import org.springframework.stereotype.Service;

@Service
public class RecibirEventoReservaService implements RecibirEventoReservaUseCase {
    private final InboxReservasPort inbox;
    private final CorrelacionarSolicitudInformacionUseCase correlacionar;

    public RecibirEventoReservaService(InboxReservasPort inbox, CorrelacionarSolicitudInformacionUseCase correlacionar) {
        this.inbox = inbox;
        this.correlacionar = correlacionar;
    }

    @Override
    public EventoInbox recibir(RecepcionEventoReserva recepcion) {
        var guardado = inbox.conservar(recepcion);
        if (guardado.pendienteDeCorrelacion()) { return correlacionar.correlacionar(guardado.id()); }
        // Una reentrega en otra posición puede recuperar la correlación pendiente del original.
        if (guardado.originalId() != null && guardado.estado() == EstadoEventoReserva.DUPLICADO) {
            correlacionar.correlacionar(guardado.originalId());
        }
        return guardado;
    }
}
