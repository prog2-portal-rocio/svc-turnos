package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.util.UUID;

public record EnvioInformacion(UUID eventId, Long procesoId, Long solicitudId, String groupId, String topic,
        String reservationProcessId, String holdId, String requestEventId, Telefono telefono,
        Instant occurredAt, Instant expiresAt, EstadoEnvioInformacion estado, UUID intentoId) {
    @Override public String toString() { return "EnvioInformacion[eventId=" + eventId + ", estado=" + estado + "]"; }
}
