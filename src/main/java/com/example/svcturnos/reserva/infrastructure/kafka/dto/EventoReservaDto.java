package com.example.svcturnos.reserva.infrastructure.kafka.dto;

import java.time.Instant;

public record EventoReservaDto(String eventId, String eventType, String reservationProcessId,
                               String holdId, Instant occurredAt, int schemaVersion) { }
