package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.model.EventoInbox;
import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.AplicarResultadoReservaPort;
import org.springframework.stereotype.Service;

@Service
public class ProcesarResultadoReservaService implements ProcesarResultadoReservaUseCase {
    private final AplicarResultadoReservaPort resultados;
    public ProcesarResultadoReservaService(AplicarResultadoReservaPort resultados) { this.resultados = resultados; }
    @Override public EventoInbox procesar(Long inboxId) { return resultados.aplicar(inboxId); }
}
