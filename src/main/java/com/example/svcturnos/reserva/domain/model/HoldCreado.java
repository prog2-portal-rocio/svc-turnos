package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;

public record HoldCreado(String holdId, String procesoExternoId, Instant expiresAt) {
    public HoldCreado {
        if (holdId == null || holdId.isBlank() || procesoExternoId == null || procesoExternoId.isBlank()
                || expiresAt == null) {
            throw new IllegalArgumentException("El hold requiere identificadores y vencimiento");
        }
    }
}
