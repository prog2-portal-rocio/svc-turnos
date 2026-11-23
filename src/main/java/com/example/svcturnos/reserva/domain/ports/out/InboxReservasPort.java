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
    /** Techo fijo del recorrido dirigido; cero si no hay solicitudes pendientes. */
    long ultimoIdSolicitudPendiente(String procesoExternoId, String holdId);
    /** Solo solicitudes canónicas pendientes, ordenadas por ID; no usa offsets sobre filas que cambian de estado. */
    List<Long> buscarSolicitudesPendientes(String procesoExternoId, String holdId, long despuesDeId, long hastaId, int limite);
    EventoInbox registrarCorrelacion(Long id, CorrelacionEvento correlacion);
}
