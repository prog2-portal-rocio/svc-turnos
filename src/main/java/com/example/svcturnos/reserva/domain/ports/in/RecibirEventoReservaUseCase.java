package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.EventoInbox;
import com.example.svcturnos.reserva.domain.model.RecepcionEventoReserva;

public interface RecibirEventoReservaUseCase {
    EventoInbox recibir(RecepcionEventoReserva recepcion);
}
