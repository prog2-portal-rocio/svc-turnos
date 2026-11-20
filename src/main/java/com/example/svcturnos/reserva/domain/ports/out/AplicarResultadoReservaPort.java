package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.EventoInbox;
public interface AplicarResultadoReservaPort { EventoInbox aplicar(Long inboxId); }
