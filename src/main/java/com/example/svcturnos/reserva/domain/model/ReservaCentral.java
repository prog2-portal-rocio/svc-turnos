package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Proyeccion de lectura: no conserva telefono ni nombres del paciente. */
public record ReservaCentral(Long reservationId, String reservationProcessId, String externalPatientId,
        LocalDate appointmentDate, LocalTime startTime, LocalTime endTime, Long professionalId,
        String professionalFirstName, String professionalLastName, EstadoReservaCentral status,
        Instant createdAt, Instant confirmedAt, Instant cancelledAt) {
    public ReservaCentral {
        if (reservationId == null || reservationId <= 0 || professionalId == null || professionalId <= 0
                || reservationProcessId == null || reservationProcessId.isBlank()
                || externalPatientId == null || externalPatientId.isBlank()
                || appointmentDate == null || startTime == null || endTime == null || status == null) {
            throw new IllegalArgumentException("Reserva central incompleta");
        }
    }
}
