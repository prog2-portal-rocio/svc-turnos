package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;

public record CancelacionReserva(String procesoExternoId, Long usuarioId, Long reservaId, EstadoCancelacion estado,
        Instant solicitadaEn, Instant actualizadaEn, Instant resultadoEn, boolean requiereReconciliacion) {
    public CancelacionReserva {
        if (procesoExternoId == null || procesoExternoId.isBlank() || usuarioId == null || usuarioId <= 0
                || reservaId == null || reservaId <= 0 || estado == null || solicitadaEn == null || actualizadaEn == null
                || actualizadaEn.isBefore(solicitadaEn)) { throw new IllegalArgumentException("Intento de cancelacion invalido"); }
        if (estado != EstadoCancelacion.CANCELADA && (resultadoEn != null || requiereReconciliacion)) {
            throw new IllegalArgumentException("Evidencia definitiva incompatible con el intento");
        }
    }
}
