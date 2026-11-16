package com.example.svcturnos.disponibilidad.application.service;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.model.*;
import com.example.svcturnos.catalogo.domain.ports.out.ConsultarCatalogoPort;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.model.*;
import com.example.svcturnos.disponibilidad.domain.ports.out.ConsultarOcupacionesPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsultarDisponibilidadServiceTest {
    private static final LocalDate FECHA = LocalDate.of(2026, 9, 14);
    private static final Instant AHORA = Instant.parse("2026-09-10T14:44:50Z");
    @Mock ConsultarCatalogoPort catalogo;
    @Mock ConsultarOcupacionesPort ocupaciones;
    private ConsultarDisponibilidadService servicio;

    @BeforeEach
    void preparar() {
        servicio = new ConsultarDisponibilidadService(catalogo, ocupaciones, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private Profesional profesional(boolean activo, boolean categoriaActiva) {
        return new Profesional(15L, 1L, "Ana", "Gomez", activo, null, null,
                new Categoria(1L, "Clinica", null, categoriaActiva, null, null));
    }

    private HorarioSemanal horario(String inicio, String fin, boolean activo) {
        return new HorarioSemanal(21L, 15L, DayOfWeek.MONDAY, LocalTime.parse(inicio), LocalTime.parse(fin),
                30, activo, null, null);
    }

    private void agenda() {
        when(catalogo.consultarProfesional(15L)).thenReturn(profesional(true, true));
        when(catalogo.consultarHorarios(15L)).thenReturn(List.of(horario("09:00", "10:10", true)));
    }

    @Test
    void sinOcupacionesDevuelveBloquesCompletosDeLaAgendaVigente() {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of());
        var resultado = servicio.consultar(15L, FECHA);
        assertEquals(2, resultado.size());
        assertEquals(LocalTime.of(9, 0), resultado.getFirst().inicio());
        assertEquals(LocalTime.of(10, 0), resultado.getLast().fin());
        verify(catalogo).consultarProfesional(15L);
        verify(catalogo).consultarHorarios(15L);
        verify(ocupaciones).consultarOcupaciones(15L, FECHA);
    }

    @Test
    void agendaParcialmenteOcupadaExcluyeSoloElInicioConfirmado() {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of(
                new Ocupacion(FECHA, LocalTime.of(9, 0), EstadoOcupacion.CONFIRMED, null)));
        var resultado = servicio.consultar(15L, FECHA);
        assertEquals(List.of(new BloqueHorario(FECHA, LocalTime.of(9, 30), LocalTime.of(10, 0))), resultado);
    }

    @Test
    void agendaCompletamenteOcupadaDevuelveListaVacia() {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of(
                new Ocupacion(FECHA, LocalTime.of(9, 0), EstadoOcupacion.CONFIRMED, null),
                new Ocupacion(FECHA, LocalTime.of(9, 30), EstadoOcupacion.HELD, AHORA.plusSeconds(1))));
        assertTrue(servicio.consultar(15L, FECHA).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"-1,2", "0,2", "1,1"})
    void soloBloqueaUnHoldQueNoHaVencido(long segundos, int cantidad) {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of(
                new Ocupacion(FECHA, LocalTime.of(9, 0), EstadoOcupacion.HELD, AHORA.plusSeconds(segundos))));
        assertEquals(cantidad, servicio.consultar(15L, FECHA).size());
    }

    @Test
    void confirmadaPrevaleceAunqueHayaUnHoldVencidoParaLaMismaPosicion() {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of(
                new Ocupacion(FECHA, LocalTime.of(9, 0), EstadoOcupacion.HELD, AHORA.minusSeconds(1)),
                new Ocupacion(FECHA, LocalTime.of(9, 0), EstadoOcupacion.CONFIRMED, null),
                new Ocupacion(FECHA, LocalTime.of(9, 0), EstadoOcupacion.CONFIRMED, null)));
        assertEquals(1, servicio.consultar(15L, FECHA).size());
    }

    @Test
    void noInfiereDuracionesNiDetectaSolapamientosParciales() {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of(
                new Ocupacion(FECHA, LocalTime.of(9, 15), EstadoOcupacion.CONFIRMED, null)));
        assertEquals(2, servicio.consultar(15L, FECHA).size());
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false", "false,false"})
    void excluyeProfesionalOCategoriaDeshabilitados(boolean profesionalActivo, boolean categoriaActiva) {
        when(catalogo.consultarProfesional(15L)).thenReturn(profesional(profesionalActivo, categoriaActiva));
        assertTrue(servicio.consultar(15L, FECHA).isEmpty());
        verify(catalogo, never()).consultarHorarios(anyLong());
        verifyNoInteractions(ocupaciones);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sinAgendaHabilitadaNoNecesitaConsultarOcupaciones(boolean listaVacia) {
        when(catalogo.consultarProfesional(15L)).thenReturn(profesional(true, true));
        when(catalogo.consultarHorarios(15L)).thenReturn(listaVacia ? List.of() : List.of(horario("09:00", "10:00", false)));
        assertTrue(servicio.consultar(15L, FECHA).isEmpty());
        verifyNoInteractions(ocupaciones);
    }

    @Test
    void diaSinAtencionNoConsultaOcupaciones() {
        agenda();
        assertTrue(servicio.consultar(15L, FECHA.plusDays(1)).isEmpty());
        verifyNoInteractions(ocupaciones);
    }

    @Test
    void conservaOrdenYEliminaDuplicadosEntreVariasJornadas() {
        when(catalogo.consultarProfesional(15L)).thenReturn(profesional(true, true));
        when(catalogo.consultarHorarios(15L)).thenReturn(List.of(horario("14:00", "14:30", true),
                horario("09:00", "10:00", true), horario("09:30", "10:00", true)));
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(List.of());
        var resultado = servicio.consultar(15L, FECHA);
        assertEquals(3, resultado.size());
        assertEquals(LocalTime.of(9, 0), resultado.getFirst().inicio());
        assertEquals(LocalTime.of(14, 0), resultado.getLast().inicio());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void validaProfesionalAntesDeConsultarDependencias(Long id) {
        assertThrows(IllegalArgumentException.class, () -> servicio.consultar(id, FECHA));
        verifyNoInteractions(catalogo, ocupaciones);
    }

    @Test
    void rechazaFechaAusente() {
        assertThrows(IllegalArgumentException.class, () -> servicio.consultar(15L, null));
        verifyNoInteractions(catalogo, ocupaciones);
    }

    @ParameterizedTest
    @EnumSource(ConsultaCatalogoException.Motivo.class)
    void propagaFalloDeCatalogoInclusoProfesionalInexistente(ConsultaCatalogoException.Motivo motivo) {
        var error = new ConsultaCatalogoException(motivo);
        when(catalogo.consultarProfesional(15L)).thenThrow(error);
        assertSame(error, assertThrows(ConsultaCatalogoException.class, () -> servicio.consultar(15L, FECHA)));
        verifyNoInteractions(ocupaciones);
    }

    @Test
    void propagaFalloAlConsultarHorarios() {
        when(catalogo.consultarProfesional(15L)).thenReturn(profesional(true, true));
        var error = new ConsultaCatalogoException(ConsultaCatalogoException.Motivo.NO_DISPONIBLE);
        when(catalogo.consultarHorarios(15L)).thenThrow(error);
        assertSame(error, assertThrows(ConsultaCatalogoException.class, () -> servicio.consultar(15L, FECHA)));
        verifyNoInteractions(ocupaciones);
    }

    @ParameterizedTest
    @EnumSource(ConsultaOcupacionesException.Motivo.class)
    void unFalloDeOcupacionesNuncaSeConvierteEnDisponibilidadTotal(ConsultaOcupacionesException.Motivo motivo) {
        agenda();
        var error = new ConsultaOcupacionesException(motivo);
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenThrow(error);
        assertSame(error, assertThrows(ConsultaOcupacionesException.class, () -> servicio.consultar(15L, FECHA)));
    }

    @Test
    void agendaInvalidaEsUnErrorDeRespuestaNoDisponibilidadVacia() {
        when(catalogo.consultarProfesional(15L)).thenReturn(profesional(true, true));
        when(catalogo.consultarHorarios(15L)).thenReturn(List.of(horario("10:00", "09:00", true)));
        var error = assertThrows(ConsultaCatalogoException.class, () -> servicio.consultar(15L, FECHA));
        assertEquals(ConsultaCatalogoException.Motivo.RESPUESTA_INVALIDA, error.getMotivo());
        verifyNoInteractions(ocupaciones);
    }

    @Test
    void respuestaIncompletaDeOcupacionesNoSeTrataComoListaVacia() {
        agenda();
        when(ocupaciones.consultarOcupaciones(15L, FECHA)).thenReturn(null)
                .thenReturn(Arrays.asList((Ocupacion) null))
                .thenReturn(List.of(new Ocupacion(FECHA.plusDays(1), LocalTime.of(9, 0), EstadoOcupacion.CONFIRMED, null)));
        for (int intento = 0; intento < 3; intento++) {
            var error = assertThrows(ConsultaOcupacionesException.class, () -> servicio.consultar(15L, FECHA));
            assertEquals(ConsultaOcupacionesException.Motivo.RESPUESTA_INVALIDA, error.getMotivo());
        }
    }
}
