package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.EventoInbox;

/** Reprocesamiento explícito de una recepción pendiente, sin operaciones REST ni transiciones de reserva. */
public interface CorrelacionarSolicitudInformacionUseCase {
    EventoInbox correlacionar(Long inboxId);
}
