package com.example.svcturnos.disponibilidad.domain.model;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DisponibilidadDiariaTest {
    @Test
    void mismaHoraEnOtraFechaNoBloqueaElCandidato() {
        LocalDate fecha = LocalDate.of(2026, 9, 14);
        var bloque = new BloqueHorario(fecha, LocalTime.of(9, 0), LocalTime.of(9, 30));
        var otraFecha = new Ocupacion(fecha.plusDays(1), bloque.inicio(), EstadoOcupacion.CONFIRMED, null);
        assertEquals(List.of(bloque), DisponibilidadDiaria.calcular(List.of(bloque), List.of(otraFecha), Instant.EPOCH));
    }

    @Test
    void listasVaciasSeConservanSinCrearBloques() {
        assertTrue(DisponibilidadDiaria.calcular(List.of(), List.of(), Instant.EPOCH).isEmpty());
    }
}
