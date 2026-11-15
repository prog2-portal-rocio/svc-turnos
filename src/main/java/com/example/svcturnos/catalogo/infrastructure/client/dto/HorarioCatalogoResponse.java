package com.example.svcturnos.catalogo.infrastructure.client.dto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

public record HorarioCatalogoResponse(Long id, Long professionalId, DayOfWeek dayOfWeek,
                                     LocalTime startTime, LocalTime endTime, Integer slotDurationMinutes,
                                     Boolean enabled, Instant createdAt, Instant updatedAt) {
}
