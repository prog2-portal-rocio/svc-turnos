package com.example.svcturnos.reserva.infrastructure.web.dto;

import com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva;
import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import java.time.Instant;

public record HoldResponse(Long procesoId, EstadoProcesoReserva estado, Instant expiresAt) {
    public static HoldResponse from(ProcesoReserva proceso) {
        return new HoldResponse(proceso.id(), proceso.estado(), proceso.vencimientoHold());
    }
}
