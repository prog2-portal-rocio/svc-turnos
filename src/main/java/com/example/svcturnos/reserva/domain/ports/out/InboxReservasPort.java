package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.*;
import java.util.List;
import java.util.Optional;

public interface InboxReservasPort {
    EventoInbox conservar(RecepcionEventoReserva recepcion);
    Optional<EventoInbox> buscarPorId(Long id);
    Optional<MensajeReserva> buscarMensajePorId(Long id);
    List<EventoInbox> buscarSolicitudesPorProceso(Long procesoId);
    List<Long> buscarResultadosPendientes(long despuesDeId, int limite);
    List<Long> buscarPendientes(int limite);
    EventoInbox registrarCorrelacion(Long id, CorrelacionEvento correlacion);
}
