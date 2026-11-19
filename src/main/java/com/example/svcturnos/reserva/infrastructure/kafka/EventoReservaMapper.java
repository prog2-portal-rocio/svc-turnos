package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.EventoReserva;
import com.example.svcturnos.reserva.domain.model.SolicitudInformacionAdicional;
import com.example.svcturnos.reserva.infrastructure.kafka.dto.EventoReservaDto;
import com.example.svcturnos.reserva.infrastructure.kafka.dto.SolicitudInformacionDto;

final class EventoReservaMapper {
    EventoReserva toDomain(EventoReservaDto dto, SolicitudInformacionDto solicitud) {
        var informacion = solicitud == null ? null : new SolicitudInformacionAdicional(solicitud.requiredField(), solicitud.message(), solicitud.expiresAt());
        return new EventoReserva(dto.eventId(), dto.eventType(), dto.reservationProcessId(), dto.holdId(),
                dto.occurredAt(), dto.schemaVersion(), informacion);
    }
}
