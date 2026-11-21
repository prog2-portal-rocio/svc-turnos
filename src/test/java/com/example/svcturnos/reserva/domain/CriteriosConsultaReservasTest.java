package com.example.svcturnos.reserva.domain;

import com.example.svcturnos.reserva.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CriteriosConsultaReservasTest {
    @Test void valoresPorDefectoYOrdenDefensivo() {
        var orden = new ArrayList<>(List.of("appointmentDate,desc", "startTime,asc"));
        var criterios = new CriteriosConsultaReservas(null, null, null, 0, 20, orden);
        orden.clear();
        assertEquals(EstadoReservaCentral.CONFIRMED, criterios.status());
        assertEquals(2, criterios.sort().size());
        var copia = criterios.sort();
        assertThrows(UnsupportedOperationException.class, copia::clear);
        assertTrue(new CriteriosConsultaReservas(null, null, null, 0, 20, null).sort().isEmpty());
    }
    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void paginacionInvalida(int page, int size) {
        assertThrows(IllegalArgumentException.class, () -> new CriteriosConsultaReservas(null, null, null, page, size, null));
    }
    @ParameterizedTest @ValueSource(strings = {"", "patientPhone,asc", "appointmentDate", "appointmentDate,ASC", "startTime,asc,desc"})
    void ordenInvalido(String valor) {
        var orden = List.of(valor);
        assertThrows(IllegalArgumentException.class, () -> new CriteriosConsultaReservas(null, null, null, 0, 20, orden));
    }
    @Test void rangoInclusivoYRechazoDeRangoInvertido() {
        var fecha = LocalDate.of(2026, 12, 1);
        assertDoesNotThrow(() -> new CriteriosConsultaReservas(null, fecha, fecha, 0, 20, null));
        var anterior = fecha.minusDays(1);
        assertThrows(IllegalArgumentException.class, () -> new CriteriosConsultaReservas(null, fecha, anterior, 0, 20, null));
    }
}
