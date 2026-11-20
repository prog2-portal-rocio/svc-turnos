package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.EventoInbox;
public interface ProcesarResultadoReservaUseCase { EventoInbox procesar(Long inboxId); }
