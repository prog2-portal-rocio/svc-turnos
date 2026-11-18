package com.example.svcturnos.reserva.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;

public record SolicitudHold(Long professionalId, LocalDate date, LocalTime startTime) {
    public SolicitudHold {
        if (professionalId == null || professionalId <= 0 || date == null || startTime == null
                || startTime.getNano() != 0) {
            throw new IllegalArgumentException("Profesional, fecha y hora local HH:mm[:ss] son obligatorios");
        }
    }
}
