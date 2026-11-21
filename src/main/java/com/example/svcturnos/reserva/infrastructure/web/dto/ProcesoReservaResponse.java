package com.example.svcturnos.reserva.infrastructure.web.dto;

import com.example.svcturnos.reserva.domain.model.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ProcesoReservaResponse(Long procesoId, String procesoExternoId, Long profesionalId, LocalDate fecha, LocalTime hora,
        EstadoProcesoReserva estado, Instant vencimientoHold, Long reservaId, Instant creadoEn,
        Instant actualizadoEn, Instant resultadoOcurridoEn) {
    public static ProcesoReservaResponse from(ProcesoReserva proceso) {
        return new ProcesoReservaResponse(proceso.id(), proceso.procesoExternoId(), proceso.profesionalId(), proceso.fecha(), proceso.hora(),
                proceso.estado(), proceso.vencimientoHold(), proceso.reservaId(), proceso.creadoEn(),
                proceso.actualizadoEn(), proceso.resultadoOcurridoEn());
    }
}
