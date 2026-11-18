package com.example.svcturnos.reserva.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.LocalTime;

public record SolicitarHoldRequest(@NotNull @Positive Long professionalId, @NotNull LocalDate date,
                                    @NotNull LocalTime startTime) { }
