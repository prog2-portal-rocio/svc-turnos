package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.model.EventoInbox;
import com.example.svcturnos.reserva.domain.model.EstadoEventoReserva;
import com.example.svcturnos.reserva.domain.model.EventoReserva;
import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import java.util.List;
import com.example.svcturnos.reserva.domain.ports.in.CorrelacionarSolicitudInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.InboxReservasPort;
import com.example.svcturnos.reserva.domain.ports.out.ProcesoReservaPersistencePort;
import org.springframework.stereotype.Service;
import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;

@Service
public class CorrelacionarSolicitudInformacionService implements CorrelacionarSolicitudInformacionUseCase {
    private final InboxReservasPort inbox;
    private final ProcesoReservaPersistencePort procesos;
    private final RecuperarConfirmacionService recuperacion;

    private final ProcesarResultadoReservaUseCase resultados;
    public CorrelacionarSolicitudInformacionService(InboxReservasPort inbox, ProcesoReservaPersistencePort procesos,
            ProcesarResultadoReservaUseCase resultados, RecuperarConfirmacionService recuperacion) {
        this.recuperacion = recuperacion;
        this.resultados = resultados;
        this.inbox = inbox;
        this.procesos = procesos;
    }

    @Override
    public EventoInbox correlacionar(Long id) {
        var recibido = inbox.buscarPorId(id).orElseThrow(() -> new IllegalArgumentException("Recepción no encontrada"));
        if (recibido.evento() != null && recibido.evento().esResultado()) { return resultados.procesar(id); }
        if (!recibido.pendienteDeCorrelacion()) { return recuperar(recibido); }
        EventoReserva evento = recibido.evento();
        List<ProcesoReserva> candidatos = List.of();
        if (evento.esSolicitudInformacion()) { candidatos = procesos.buscarPorProcesoExternoId(evento.reservationProcessId()); }
        return recuperar(inbox.registrarCorrelacion(id, evento.correlacionar(candidatos)));
    }

    private EventoInbox recuperar(EventoInbox recibido) {
        if (recibido.estado() == EstadoEventoReserva.SOLICITUD_REGISTRADA) {
            procesos.buscarPorId(recibido.procesoLocalId()).ifPresent(recuperacion::recuperar);
        }
        return recibido;
    }
}
