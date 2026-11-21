package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.util.List;

public record EventoReserva(String eventId, String eventType, String reservationProcessId, String holdId,
                             Instant occurredAt, int schemaVersion, SolicitudInformacionAdicional solicitud, ResultadoReserva resultado) {
    public static final String TIPO_SOLICITUD_INFORMACION = "AdditionalInformationRequested";

    public EventoReserva(String eventId, String eventType, String reservationProcessId, String holdId,
            Instant occurredAt, int schemaVersion, SolicitudInformacionAdicional solicitud) {
        this(eventId, eventType, reservationProcessId, holdId, occurredAt, schemaVersion, solicitud, null);
    }

    public EventoReserva {
        if (resultado != null && (!resultado.tipo().evento().equals(eventType) || solicitud != null)) {
            throw new IllegalArgumentException("Tipo de resultado inconsistente");
        }
        obligatorio(eventId);
        obligatorio(eventType);
        obligatorio(reservationProcessId);
        obligatorio(holdId);
        if (occurredAt == null || schemaVersion != 1 || (TIPO_SOLICITUD_INFORMACION.equals(eventType) != (solicitud != null))) {
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

    public boolean esResultado() { return TipoResultadoReserva.deEvento(eventType).isPresent(); }

    public boolean esSolicitudInformacion() { return TIPO_SOLICITUD_INFORMACION.equals(eventType); }
}
