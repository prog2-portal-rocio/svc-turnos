package com.example.svcturnos.reserva.infrastructure.web.dto;

import com.example.svcturnos.reserva.domain.model.*;
import java.time.Instant;
import java.util.UUID;

public record EnvioInformacionResponse(Long procesoId, UUID eventId, EstadoEnvioInformacion publicacion, Instant expiresAt) {
    public static EnvioInformacionResponse from(EnvioInformacion envio) {
        return new EnvioInformacionResponse(envio.procesoId(), envio.eventId(), envio.estado(), envio.expiresAt());
    }
}
