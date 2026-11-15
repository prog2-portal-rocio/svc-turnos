package com.example.svcturnos.catalogo.domain.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

public record HorarioSemanal(Long id, Long professionalId, DayOfWeek dayOfWeek,
                            LocalTime startTime, LocalTime endTime, Integer slotDurationMinutes,
                            boolean enabled, Instant createdAt, Instant updatedAt) {
}
