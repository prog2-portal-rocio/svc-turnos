package com.example.svcturnos.disponibilidad.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Posición ocupada del contrato v1; no contiene un intervalo ni una duración. */
public record Ocupacion(LocalDate fecha, LocalTime inicio, EstadoOcupacion estado, Instant venceEn) {
    public Ocupacion {
        if (fecha == null || inicio == null || estado == null) {
            throw new IllegalArgumentException("La ocupación requiere fecha, inicio y estado");
        }
        if ((estado == EstadoOcupacion.HELD) != (venceEn != null)) {
            throw new IllegalArgumentException("Solo un hold requiere vencimiento");
        }
    }

    public boolean vigente(Instant ahora) {
        return estado == EstadoOcupacion.CONFIRMED || venceEn.isAfter(ahora);
    }
}
