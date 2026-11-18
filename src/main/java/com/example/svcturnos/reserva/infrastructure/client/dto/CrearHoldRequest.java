package com.example.svcturnos.reserva.infrastructure.client.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record CrearHoldRequest(Long professionalId, LocalDate date, LocalTime startTime, String externalPatientId) { }
