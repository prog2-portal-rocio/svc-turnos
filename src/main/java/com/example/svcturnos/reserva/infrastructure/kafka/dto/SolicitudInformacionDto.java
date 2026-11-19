package com.example.svcturnos.reserva.infrastructure.kafka.dto;

import java.time.Instant;

public record SolicitudInformacionDto(String requiredField, String message, Instant expiresAt) { }
