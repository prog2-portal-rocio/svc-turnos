package com.example.svcturnos.disponibilidad.domain.model;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class OcupacionTest {
    @Test
    void confirmadaSiempreVigenteYHoldSoloAntesDelVencimiento() {
        Instant ahora = Instant.parse("2026-09-10T14:44:50Z");
        LocalDate fecha = LocalDate.of(2026, 9, 14);
        LocalTime inicio = LocalTime.of(9, 0);
        var confirmada = new Ocupacion(fecha, inicio, EstadoOcupacion.CONFIRMED, null);
        var hold = new Ocupacion(fecha, inicio, EstadoOcupacion.HELD, ahora);
        assertTrue(confirmada.vigente(ahora.plusSeconds(300)));
        assertTrue(hold.vigente(ahora.minusSeconds(1)));
        assertFalse(hold.vigente(ahora));
        assertFalse(hold.vigente(ahora.plusSeconds(1)));
    }

    @Test
    void rechazaDatosQueNoCumplenElContrato() {
        LocalDate fecha = LocalDate.of(2026, 9, 14);
        LocalTime inicio = LocalTime.of(9, 0);
        Instant vence = Instant.parse("2026-09-10T14:44:50Z");
        assertThrows(IllegalArgumentException.class, () -> new Ocupacion(fecha, inicio, EstadoOcupacion.HELD, null));
        assertThrows(IllegalArgumentException.class, () -> new Ocupacion(fecha, inicio, EstadoOcupacion.CONFIRMED, vence));
        assertThrows(IllegalArgumentException.class, () -> new Ocupacion(null, inicio, EstadoOcupacion.CONFIRMED, null));
    }
}
