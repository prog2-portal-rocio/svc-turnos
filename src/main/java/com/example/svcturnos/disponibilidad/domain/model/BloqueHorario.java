package com.example.svcturnos.disponibilidad.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;

/** Intervalo local semiabierto [inicio, fin); no garantiza una reserva. */
public record BloqueHorario(LocalDate fecha, LocalTime inicio, LocalTime fin) {
    public BloqueHorario {
        if (fecha == null || inicio == null || fin == null || !inicio.isBefore(fin)) {
            throw new IllegalArgumentException("El bloque debe tener fecha e intervalo válido dentro del mismo día");
        }
    }
}
