package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.InboxReservasPort;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RecuperarResultadosReservaService {
    private static final Logger LOG = LoggerFactory.getLogger(RecuperarResultadosReservaService.class);
    private final InboxReservasPort inbox;
    private final ProcesarResultadoReservaUseCase resultados;
    public RecuperarResultadosReservaService(InboxReservasPort inbox, ProcesarResultadoReservaUseCase resultados) {
        this.inbox = inbox; this.resultados = resultados;
    }
    public long ultimoIdPendiente() { return inbox.ultimoIdResultadoPendiente(); }

    /** Cursor del último intento, no un acuse de éxito. Cero finaliza este recorrido acotado. */
    public long recuperar(long despues, long hasta, int limite) {
        var ids = inbox.buscarResultadosPendientes(despues, hasta, limite);
        for (var id : ids) {
            try { resultados.procesar(id); }
            catch (RuntimeException _) {
                // Cada aplicación tiene su propia transacción; el rollback mantiene el evento recuperable.
                LOG.warn("No se pudo recuperar el resultado; permanece pendiente en la inbox: recepcion={}", id);
            }
        }
        return ids.isEmpty() || ids.getLast() >= hasta ? 0 : ids.getLast();
    }
}
