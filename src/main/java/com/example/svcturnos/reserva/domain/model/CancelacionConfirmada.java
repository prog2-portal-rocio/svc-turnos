package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;

/** Evidencia definitiva; la fecha puede faltar en una consulta REST de una cancelacion anterior. */
public record CancelacionConfirmada(Long reservationId, String reservationProcessId, Instant resultadoEn) {
    public CancelacionConfirmada {
        if (reservationId == null || reservationId <= 0 || reservationProcessId == null || reservationProcessId.isBlank()) {
            throw new IllegalArgumentException("Evidencia de cancelacion incompleta");
        }
    }
}
