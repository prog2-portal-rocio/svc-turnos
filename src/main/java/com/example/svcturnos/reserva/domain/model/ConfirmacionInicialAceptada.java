package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;

/** Evidencia de aceptación inicial WAITING_FOR_PHONE, aunque llegue vencida; no es una reserva confirmada. */
public record ConfirmacionInicialAceptada(String reservationProcessId, Instant expiresAt) {
    public ConfirmacionInicialAceptada {
        if (reservationProcessId == null || reservationProcessId.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Aceptación inicial incompleta");
        }
    }
}
