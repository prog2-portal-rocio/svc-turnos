package com.example.svcturnos.disponibilidad.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcupacionesResponse(Long professionalId, LocalDate from, LocalDate to,
                                  List<Posicion> occupancies) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Posicion(LocalDate date, LocalTime startTime, String status, Instant expiresAt) { }
}
