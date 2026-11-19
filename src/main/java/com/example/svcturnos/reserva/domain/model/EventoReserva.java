package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.util.List;

public record EventoReserva(String eventId, String eventType, String reservationProcessId, String holdId,
                             Instant occurredAt, int schemaVersion, SolicitudInformacionAdicional solicitud) {
    public static final String SOLICITUD = "AdditionalInformationRequested";

    public EventoReserva {
        obligatorio(eventId);
        obligatorio(eventType);
        obligatorio(reservationProcessId);
        obligatorio(holdId);
        if (occurredAt == null || schemaVersion != 1 || (SOLICITUD.equals(eventType) != (solicitud != null))) {
            throw new IllegalArgumentException("Evento de reservas v1 inválido");
        }
    }

    private static void obligatorio(String dato) {
        if (dato == null || dato.isBlank()) { throw new IllegalArgumentException("Campo del evento obligatorio"); }
    }

    public CorrelacionEvento correlacionar(List<ProcesoReserva> procesos) {
        if (!esSolicitudInformacion()) { return new CorrelacionEvento(EstadoEventoReserva.PENDIENTE_TIPO, null); }
        if (procesos.isEmpty()) { return new CorrelacionEvento(EstadoEventoReserva.PENDIENTE_CORRELACION, null); }
        if (procesos.size() != 1) { return new CorrelacionEvento(EstadoEventoReserva.CORRELACION_INCONSISTENTE, null); }
        var proceso = procesos.getFirst();
        if (proceso.id() == null || !reservationProcessId.equals(proceso.procesoExternoId()) || !holdId.equals(proceso.holdId())) {
            return new CorrelacionEvento(EstadoEventoReserva.CORRELACION_INCONSISTENTE, null);
        }
        return new CorrelacionEvento(EstadoEventoReserva.SOLICITUD_REGISTRADA, proceso.id());
    }

    public boolean esSolicitudInformacion() { return SOLICITUD.equals(eventType); }
}
