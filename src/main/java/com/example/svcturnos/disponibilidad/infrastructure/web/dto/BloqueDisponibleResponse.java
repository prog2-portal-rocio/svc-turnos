package com.example.svcturnos.disponibilidad.infrastructure.web.dto;

import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;

/** Horas locales de atención; consultar un bloque no garantiza su reserva. */
public record BloqueDisponibleResponse(LocalDate fecha,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime inicio,
        @JsonFormat(pattern = "HH:mm:ss") LocalTime fin) {
    public static BloqueDisponibleResponse from(BloqueHorario bloque) {
        return new BloqueDisponibleResponse(bloque.fecha(), bloque.inicio(), bloque.fin());
    }
}
