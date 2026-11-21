package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsultarReservasServiceTest {
    final UsuarioAutenticadoPort usuario = mock(UsuarioAutenticadoPort.class);
    final ProcesoReservaPersistencePort procesos = mock(ProcesoReservaPersistencePort.class);
    final ConsultarReservasRemotasPort remoto = mock(ConsultarReservasRemotasPort.class);
    final ConsultarReservasService servicio = new ConsultarReservasService(usuario, procesos, remoto);
    static final Instant AHORA = Instant.parse("2026-11-20T10:00:00Z");
    static final LocalDate FECHA = LocalDate.of(2026, 12, 1);
    static final LocalTime HORA = LocalTime.of(9, 30);
    final CriteriosConsultaReservas criterios = new CriteriosConsultaReservas(null, null, null, 0, 20, List.of());

    ProcesoReserva proceso(Long propietario, EstadoProcesoReserva estado) {
        boolean hold = estado.requiereDatosHold();
        boolean reserva = estado == EstadoProcesoReserva.CONFIRMADA || estado == EstadoProcesoReserva.CANCELADA;
        boolean resultado = reserva || estado == EstadoProcesoReserva.PROCESO_EXPIRADO || estado == EstadoProcesoReserva.PROCESO_INVALIDO;
        return new ProcesoReserva(11L, propietario, 15L, FECHA, HORA, estado,
                hold ? "hold" : null, hold ? "externo" : null, hold ? AHORA.plusSeconds(600) : null,
                AHORA, AHORA, 0L, reserva ? 8251L : null, resultado ? AHORA : null);
    }

    ReservaCentral reserva(String paciente, Long profesional, LocalDate fecha, LocalTime hora, Long id) {
        return new ReservaCentral(id, "externo", paciente, fecha, hora, LocalTime.of(10, 0),
                profesional, "Ana", "Gomez", EstadoReservaCentral.CONFIRMED, AHORA, AHORA, null);
    }

    @ParameterizedTest @EnumSource(EstadoProcesoReserva.class)
    void conservaTodosLosEstadosSinConvertirPendientesEnConfirmados(EstadoProcesoReserva estado) {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        var proceso = proceso(7L, estado);
        when(procesos.buscarPorUsuario(7L)).thenReturn(List.of(proceso));
        assertEquals(List.of(proceso), servicio.consultarProcesos());
        verifyNoInteractions(remoto);
        verify(procesos, never()).guardar(any());
    }

    @Test void procesosVaciosNoConsultanLaCatedra() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(procesos.buscarPorUsuario(7L)).thenReturn(List.of());
        assertTrue(servicio.consultarProcesos().isEmpty());
        verifyNoInteractions(remoto);
    }

    @Test void detalleSoloPermiteElPropietarioYNoDistingueAjenoDeInexistente() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        var propio = proceso(7L, EstadoProcesoReserva.CREADO);
        when(procesos.buscarPorId(11L)).thenReturn(Optional.of(propio));
        assertEquals(propio, servicio.consultarProceso(11L));
        when(procesos.buscarPorId(11L)).thenReturn(Optional.of(proceso(8L, EstadoProcesoReserva.CREADO)));
        assertEquals(ConsultaReservasException.Motivo.PROCESO_NO_ENCONTRADO,
                assertThrows(ConsultaReservasException.class, () -> servicio.consultarProceso(11L)).getMotivo());
        when(procesos.buscarPorId(11L)).thenReturn(Optional.empty());
        assertThrows(ConsultaReservasException.class, () -> servicio.consultarProceso(11L));
        assertThrows(IllegalArgumentException.class, () -> servicio.consultarProceso(0L));
    }

    @Test void consultaCentralUsaPacienteEstableYNoModificaElEstadoLocalPendiente() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of(proceso(7L, EstadoProcesoReserva.ESPERANDO_TELEFONO)));
        var pagina = new PaginaReservas(List.of(reserva("user-7", 15L, FECHA, HORA, 8251L)), 1, 0, 20);
        when(remoto.consultar("user-7", criterios)).thenReturn(pagina);
        assertEquals(pagina, servicio.consultarReservas(criterios));
        verify(procesos, never()).guardar(any());
    }

    @Test void noFiltraUnaPaginaAjenaNiOcultaErroresComoListaVacia() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of(proceso(7L, EstadoProcesoReserva.CONFIRMADA)));
        var invalidas = List.of(reserva("user-8", 15L, FECHA, HORA, 8251L),
                reserva("user-7", 16L, FECHA, HORA, 8251L), reserva("user-7", 15L, FECHA.plusDays(1), HORA, 8251L),
                reserva("user-7", 15L, FECHA, HORA.plusMinutes(30), 8251L), reserva("user-7", 15L, FECHA, HORA, 999L));
        for (var reserva : invalidas) {
            when(remoto.consultar("user-7", criterios)).thenReturn(new PaginaReservas(List.of(reserva), 1, 0, 20));
            assertEquals(ConsultaReservasException.Motivo.RESPUESTA_INVALIDA,
                    assertThrows(ConsultaReservasException.class, () -> servicio.consultarReservas(criterios)).getMotivo());
        }
    }

    @Test void reservaPropiaSinAsociacionLocalConservaLaPaginaYElTotal() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        var filtros = new CriteriosConsultaReservas(null, null, null, 1, 20, List.of());
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of());
        var pagina = new PaginaReservas(List.of(reserva("user-7", 15L, FECHA, HORA, 8251L)), 21, 1, 20);
        when(remoto.consultar("user-7", filtros)).thenReturn(pagina);
        assertSame(pagina, servicio.consultarReservas(filtros));
        assertEquals(21, pagina.totalElements());
        verify(procesos, never()).guardar(any());
    }

    @Test void listaCentralVaciaEsValidaYUnFalloSePropaga() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(remoto.consultar("user-7", criterios)).thenReturn(new PaginaReservas(List.of(), 0, 0, 20));
        assertTrue(servicio.consultarReservas(criterios).content().isEmpty());
        var error = new ConsultaReservasException(ConsultaReservasException.Motivo.SERVICIO_NO_DISPONIBLE);
        when(remoto.consultar("user-7", criterios)).thenThrow(error);
        assertSame(error, assertThrows(ConsultaReservasException.class, () -> servicio.consultarReservas(criterios)));
    }
    @Test void correlacionAmbiguaOPropietarioInconsistenteNoExponenUnaReserva() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        var propio = proceso(7L, EstadoProcesoReserva.CONFIRMADA);
        when(remoto.consultar("user-7", criterios)).thenReturn(
                new PaginaReservas(List.of(reserva("user-7", 15L, FECHA, HORA, 8251L)), 1, 0, 20));
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of(propio, propio));
        assertThrows(ConsultaReservasException.class, () -> servicio.consultarReservas(criterios));
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of(proceso(8L, EstadoProcesoReserva.CONFIRMADA)));
        assertThrows(ConsultaReservasException.class, () -> servicio.consultarReservas(criterios));
    }

    @Test void paginaMixtaConYSinProcesoNoOcultaFilasNiRecalculaElTotal() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        var filtros = new CriteriosConsultaReservas(null, null, null, 1, 20, List.of());
        var local = proceso(7L, EstadoProcesoReserva.CONFIRMADA);
        var conocida = reserva("user-7", 15L, FECHA, HORA, 8251L);
        var sinProceso = new ReservaCentral(9000L, "otro-externo", "user-7", FECHA, HORA,
                LocalTime.of(10, 0), 15L, "Ana", "Gomez", EstadoReservaCentral.CONFIRMED, AHORA, AHORA, null);
        var pagina = new PaginaReservas(List.of(conocida, sinProceso), 42, 1, 20);
        when(remoto.consultar("user-7", filtros)).thenReturn(pagina);
        when(procesos.buscarPorProcesosExternos(List.of("externo", "otro-externo"))).thenReturn(List.of(local));
        assertSame(pagina, servicio.consultarReservas(filtros));
        verify(procesos).buscarPorProcesosExternos(List.of("externo", "otro-externo"));
        verify(procesos, never()).buscarPorUsuario(any());
        verify(procesos, never()).buscarPorProcesoExternoId(any());
    }

    @Test void respuestaAjenaSinProcesoLocalTambienSeRechazaAntesDeConsultarLaBase() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(remoto.consultar("user-7", criterios)).thenReturn(
                new PaginaReservas(List.of(reserva("user-8", 15L, FECHA, HORA, 8251L)), 1, 0, 20));
        assertThrows(ConsultaReservasException.class, () -> servicio.consultarReservas(criterios));
        verifyNoInteractions(procesos);
    }

}
