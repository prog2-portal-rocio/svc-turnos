package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;

public record SolicitudInformacionAdicional(String requiredField, String message, Instant expiresAt) {
    public SolicitudInformacionAdicional {
        if (!"PHONE_NUMBER".equals(requiredField) || message == null || message.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Solicitud de información v1 incompleta o no soportada");
        }
    }
}
