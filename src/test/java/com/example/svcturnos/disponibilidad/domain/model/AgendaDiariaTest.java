package com.example.svcturnos.disponibilidad.domain.model;

import com.example.svcturnos.catalogo.domain.model.HorarioSemanal;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgendaDiariaTest {
    private static final LocalDate LUNES = LocalDate.of(2026, 9, 14);
    private HorarioSemanal horario(String inicio, String fin, int minutos, boolean habilitado) {
        return new HorarioSemanal(21L, 15L, DayOfWeek.MONDAY, LocalTime.parse(inicio),
                LocalTime.parse(fin), minutos, habilitado, null, null);
    }

    @Test
    void generaBloquesAlineadosCompletosIncluyeAperturaYUltimoBloque() {
        var agenda = List.of(horario("09:00", "10:10", 30, true));
        var bloques = AgendaDiaria.generar(15L, LUNES, agenda);
        assertEquals(List.of(new BloqueHorario(LUNES, LocalTime.of(9, 0), LocalTime.of(9, 30)),
                new BloqueHorario(LUNES, LocalTime.of(9, 30), LocalTime.of(10, 0))), bloques);
    }

    @Test
    void variasJornadasSeOrdenanYLosBloquesRepetidosNoSeDuplican() {
        var agenda = List.of(horario("14:00", "14:30", 30, true),
                horario("09:00", "10:00", 30, true), horario("09:30", "10:00", 30, true));
        var bloques = AgendaDiaria.generar(15L, LUNES, agenda);
        assertEquals(3, bloques.size());
        assertEquals(LocalTime.of(9, 0), bloques.getFirst().inicio());
        assertEquals(LocalTime.of(14, 0), bloques.getLast().inicio());
    }

    @Test
    void ignoraHorariosDeshabilitadosDiasSinAtencionYJornadasSinBloqueCompleto() {
        var agenda = List.of(horario("09:00", "10:00", 30, false), horario("10:00", "10:20", 30, true));
        assertTrue(AgendaDiaria.generar(15L, LUNES, agenda).isEmpty());
        assertTrue(AgendaDiaria.generar(15L, LUNES.plusDays(1), agenda).isEmpty());
        assertTrue(AgendaDiaria.generar(15L, LUNES, List.of()).isEmpty());
    }

    @Test
    void rechazaDuracionesInvalidasYJornadasQueAtraviesanMedianocheComoCatalogo() {
        var sinDuracion = List.of(horario("09:00", "10:00", 0, true));
        var nocturna = List.of(horario("23:00", "01:00", 30, true));
        assertThrows(IllegalArgumentException.class, () -> AgendaDiaria.generar(15L, LUNES, sinDuracion));
        assertThrows(IllegalArgumentException.class, () -> AgendaDiaria.generar(15L, LUNES, nocturna));
    }

    @Test
    void conservaLosSegundosYLaAlineacionDesdeElInicioDeLaJornada() {
        var agenda = List.of(horario("09:00:15", "10:00:15", 30, true));
        var bloques = AgendaDiaria.generar(15L, LUNES, agenda);
        assertEquals(LocalTime.of(9, 30, 15), bloques.getLast().inicio());
        assertEquals(LocalTime.of(10, 0, 15), bloques.getLast().fin());
    }
}
