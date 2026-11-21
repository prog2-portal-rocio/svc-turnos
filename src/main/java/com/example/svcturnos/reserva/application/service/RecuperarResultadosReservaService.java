package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.InboxReservasPort;
import org.springframework.stereotype.Service;

@Service
public class RecuperarResultadosReservaService {
    private final InboxReservasPort inbox;
    private final ProcesarResultadoReservaUseCase resultados;
    public RecuperarResultadosReservaService(InboxReservasPort inbox, ProcesarResultadoReservaUseCase resultados) {
        this.inbox = inbox; this.resultados = resultados;
    }
    /** Cursor circular: un proceso desconocido no bloquea los resultados posteriores. */
    public long recuperar(long despues, int limite) {
        var ids = inbox.buscarResultadosPendientes(despues, limite);
        for (var id : ids) { resultados.procesar(id); }
        return ids.isEmpty() ? 0 : ids.getLast();
    }
}
