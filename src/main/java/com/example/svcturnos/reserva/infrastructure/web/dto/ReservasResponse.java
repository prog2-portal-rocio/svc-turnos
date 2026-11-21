package com.example.svcturnos.reserva.infrastructure.web.dto;

import com.example.svcturnos.reserva.domain.model.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ReservasResponse(List<ReservaResponse> content, long totalElements, int page, int size) {
    public static ReservasResponse from(PaginaReservas pagina) {
        return new ReservasResponse(pagina.content().stream().map(ReservaResponse::from).toList(),
                pagina.totalElements(), pagina.page(), pagina.size());
    }

    public record ReservaResponse(Long reservationId, String reservationProcessId, Long professionalId,
            String professionalFirstName, String professionalLastName, LocalDate appointmentDate,
            LocalTime startTime, LocalTime endTime, EstadoReservaCentral status,
            Instant createdAt, Instant confirmedAt, Instant cancelledAt) {
        public static ReservaResponse from(ReservaCentral reserva) {
            return new ReservaResponse(reserva.reservationId(), reserva.reservationProcessId(), reserva.professionalId(),
                    reserva.professionalFirstName(), reserva.professionalLastName(), reserva.appointmentDate(),
                    reserva.startTime(), reserva.endTime(), reserva.status(), reserva.createdAt(),
                    reserva.confirmedAt(), reserva.cancelledAt());
        }
    }
}
