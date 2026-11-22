package com.example.svcturnos.reserva.infrastructure.client.dto;

import com.example.svcturnos.reserva.domain.model.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservaCentralDto(Long reservationId, String reservationProcessId, String externalPatientId,
        LocalDate appointmentDate, LocalTime startTime, LocalTime endTime, Long professionalId,
        String professionalFirstName, String professionalLastName, EstadoReservaCentral status,
        Instant createdAt, Instant confirmedAt, Instant cancelledAt) {
    public ReservaCentral toDomain() {
        return new ReservaCentral(reservationId, reservationProcessId, externalPatientId, appointmentDate,
                startTime, endTime, professionalId, professionalFirstName, professionalLastName,
                status, createdAt, confirmedAt, cancelledAt);
    }
}
