package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.*;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.CorrelacionarSolicitudInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.*;

class ConfirmarHoldServiceTest {
    final UsuarioAutenticadoPort usuario = mock(UsuarioAutenticadoPort.class);
    final ProcesoReservaPersistencePort procesos = mock(ProcesoReservaPersistencePort.class);
    final ConfirmarHoldRemotoPort remoto = mock(ConfirmarHoldRemotoPort.class);
    final InboxReservasPort inbox = mock(InboxReservasPort.class);
    final CorrelacionarSolicitudInformacionUseCase correlacionar = mock(CorrelacionarSolicitudInformacionUseCase.class);
    final Instant ahora = Instant.parse("2026-11-19T12:00:00Z");
    final PacienteReserva paciente = new PacienteReserva(7L, "Juan", "Perez");
    final ConfirmarHoldService servicio = new ConfirmarHoldService(usuario, procesos, remoto, inbox, correlacionar,
            Clock.fixed(ahora, ZoneOffset.UTC), new RecuperarConfirmacionService(inbox, procesos, Clock.fixed(ahora, ZoneOffset.UTC)));
    ProcesoReserva hold;

    @BeforeEach void preparar() {
        hold = proceso(HOLD_REGISTRADO, ahora.plusSeconds(300));
        when(usuario.obtenerPaciente()).thenReturn(paciente);
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(hold));
        when(procesos.guardar(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    ProcesoReserva proceso(EstadoProcesoReserva estado, Instant vence) {
        boolean datos = estado.requiereDatosHold();
        return new ProcesoReserva(42L, 7L, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30), estado,
                datos ? "hold" : null, datos ? "externo" : null, datos ? vence : null, ahora.minusSeconds(60), ahora, 1L);
    }

    @Test void exito202EsEsperaDeTelefonoConIdentidadPersistidaYVencimientoRemoto() {
        var vence = ahora.plusSeconds(200);
        when(remoto.confirmar("hold", paciente)).thenReturn(new ConfirmacionInicialAceptada("externo", vence));
        var resultado = servicio.confirmar(42L);
        assertEquals(ESPERANDO_TELEFONO, resultado.estado());
        assertEquals(vence, resultado.vencimientoHold());
        assertEquals("externo", resultado.procesoExternoId());
        assertEquals("user-7", paciente.externalPatientId());
        var orden = inOrder(procesos, remoto);
        orden.verify(procesos).buscarPorId(42L);
        orden.verify(procesos).guardar(argThat(p -> p.estado() == CONFIRMACION_SOLICITADA));
        orden.verify(remoto).confirmar("hold", paciente);
        orden.verify(procesos).guardar(argThat(p -> p.estado() == ESPERANDO_TELEFONO));
    }

    @Test void procesoAjenoNoRevelaIdentificadoresNiEnviaPost() {
        when(usuario.obtenerPaciente()).thenReturn(new PacienteReserva(8L, "Otro", "Paciente"));
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.PROCESO_AJENO, error.getMotivo());
        assertNull(error.getProcesoId());
        verify(procesos, never()).guardar(any());
        verifyNoInteractions(remoto);
    }

    @Test void inexistenteNoEnviaPost() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.empty());
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.PROCESO_INEXISTENTE, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1})
    void identificadorLocalInvalido(Long id) {
        assertThrows(IllegalArgumentException.class, () -> servicio.confirmar(id));
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class, names = {"CREADO", "HOLD_SOLICITADO", "HOLD_RECHAZADO",
            "CONFIRMACION_SOLICITADA", "CONFIRMACION_INCIERTA", "HOLD_VENCIDO", "HOLD_NO_RECUPERABLE"})
    void estadosIncompatiblesNoRepitenPost(EstadoProcesoReserva estado) {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(estado, ahora.plusSeconds(300))));
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.ESTADO_INCOMPATIBLE, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0})
    void vencimientoInclusivoImpidePostYQuedaRegistrado(long segundos) {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(HOLD_REGISTRADO, ahora.plusSeconds(segundos))));
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.HOLD_VENCIDO, error.getMotivo());
        verify(procesos).guardar(argThat(p -> p.estado() == HOLD_VENCIDO));
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @CsvSource({"RESULTADO_INCIERTO,CONFIRMACION_INCIERTA", "PROCESO_YA_CONFIRMADO,CONFIRMACION_INCIERTA",
            "HOLD_VENCIDO,HOLD_VENCIDO", "HOLD_NO_ENCONTRADO,HOLD_NO_RECUPERABLE", "SOLICITUD_RECHAZADA,CONFIRMACION_RECHAZADA",
            "CREDENCIAL_TECNICA_RECHAZADA,CONFIRMACION_RECHAZADA", "CONFIGURACION_INCOMPLETA,CONFIRMACION_RECHAZADA"})
    void registraProgresoSinInventarExitoNiReintentar(ConfirmarHoldException.Motivo motivo, EstadoProcesoReserva estado) {
        when(remoto.confirmar("hold", paciente)).thenThrow(new ConfirmarHoldException(motivo));
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(motivo, error.getMotivo());
        assertEquals(42L, error.getProcesoId());
        verify(procesos).guardar(argThat(p -> p.estado() == estado && "hold".equals(p.holdId())));
        verify(remoto, times(1)).confirmar("hold", paciente);
    }

    @ParameterizedTest @ValueSource(strings = {"otro-proceso", "null", "excepcion"})
    void respuestaInconsistenteConservaIncertidumbre(String caso) {
        if (caso.equals("excepcion")) { when(remoto.confirmar(any(), any())).thenThrow(new IllegalStateException()); }
        else if (!caso.equals("null")) {
            var respuesta = caso.equals("otro-proceso") ? new ConfirmacionInicialAceptada("otro", ahora.plusSeconds(100))
                    : new ConfirmacionInicialAceptada("externo", ahora.plusSeconds(301));
            when(remoto.confirmar(any(), any())).thenReturn(respuesta);
        }
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
        verify(procesos).guardar(argThat(p -> p.estado() == CONFIRMACION_INCIERTA));
    }

    @Test void perdedorOptimistaNoEnviaPost() {
        when(procesos.guardar(any())).thenThrow(new ProcesoReservaConcurrenteException());
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.ESTADO_INCOMPATIBLE, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @Test void falloDeIntencionNoEnviaPost() {
        when(procesos.guardar(any())).thenThrow(new IllegalStateException());
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.PERSISTENCIA_FALLIDA, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @Test void respuestaYaPersistidaNoRepitePostYSoloRecuperaCorrelacion() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora.plusSeconds(200))));
        when(inbox.ultimoIdSolicitudPendiente("externo", "hold")).thenReturn(2L);
        when(inbox.buscarSolicitudesPendientes("externo", "hold", 0, 2, 100)).thenReturn(List.of(1L, 2L));
        var evento = new EventoReserva("request-original", EventoReserva.TIPO_SOLICITUD_INFORMACION, "externo", "hold", ahora, 1,
                new SolicitudInformacionAdicional("PHONE_NUMBER", "Ingrese telefono", ahora.plusSeconds(200)));
        when(inbox.buscarPorId(1L)).thenReturn(Optional.of(new EventoInbox(1L, evento, EstadoEventoReserva.PENDIENTE_CORRELACION, null, null)));
        when(inbox.buscarPorId(2L)).thenReturn(Optional.empty());
        assertEquals(ESPERANDO_TELEFONO, servicio.confirmar(42L).estado());
        verify(correlacionar).correlacionar(1L);
        verifyNoInteractions(remoto);
    }

    @Test void falloDeInboxNoDeshaceAceptacionNiPermiteReenviar() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora.plusSeconds(200))));
        when(inbox.ultimoIdSolicitudPendiente("externo", "hold")).thenThrow(new IllegalStateException());
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.CORRELACION_PENDIENTE, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @Test void recorreTodosLosLotesDirigidosConCursorSinLimiteGlobal() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora.plusSeconds(200))));
        when(inbox.ultimoIdSolicitudPendiente("externo", "hold")).thenReturn(205L);
        when(inbox.buscarSolicitudesPendientes(eq("externo"), eq("hold"), anyLong(), eq(205L), eq(100)))
                .thenAnswer(invocation -> {
                    long desde = invocation.getArgument(2);
                    return java.util.stream.LongStream.rangeClosed(desde + 1, Math.min(desde + 100, 205)).boxed().toList();
                });
        when(inbox.buscarPorId(anyLong())).thenAnswer(invocation -> Optional.of(pendiente(invocation.getArgument(0), "externo", "hold")));
        assertEquals(ESPERANDO_TELEFONO, servicio.confirmar(42L).estado());
        verify(inbox).buscarSolicitudesPendientes("externo", "hold", 0, 205, 100);
        verify(inbox).buscarSolicitudesPendientes("externo", "hold", 100, 205, 100);
        verify(inbox).buscarSolicitudesPendientes("externo", "hold", 200, 205, 100);
        verify(inbox, times(1)).ultimoIdSolicitudPendiente("externo", "hold");
        verify(correlacionar, times(205)).correlacionar(anyLong());
        verifyNoInteractions(remoto);
    }

    EventoInbox pendiente(Long id, String externo, String holdId) {
        var evento = new EventoReserva("request-" + id, EventoReserva.TIPO_SOLICITUD_INFORMACION, externo, holdId, ahora, 1,
                new SolicitudInformacionAdicional("PHONE_NUMBER", "Ingrese telefono", ahora.plusSeconds(100)));
        return new EventoInbox(id, evento, EstadoEventoReserva.PENDIENTE_CORRELACION, null, null);
    }

    @Test void noCorrelacionaIdentificadoresAjenosAunqueElPuertoLosDevuelva() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora.plusSeconds(200))));
        when(inbox.ultimoIdSolicitudPendiente("externo", "hold")).thenReturn(2L);
        when(inbox.buscarSolicitudesPendientes("externo", "hold", 0, 2, 100)).thenReturn(List.of(1L, 2L));
        when(inbox.buscarPorId(1L)).thenReturn(Optional.of(pendiente(1L, "externo", "hold-ajeno")));
        when(inbox.buscarPorId(2L)).thenReturn(Optional.of(pendiente(2L, "externo-ajeno", "hold")));
        assertEquals(ESPERANDO_TELEFONO, servicio.confirmar(42L).estado());
        verifyNoInteractions(correlacionar, remoto);
    }

    @Test void loteVacioPorCorrelacionConcurrenteFinalizaElRecorrido() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora.plusSeconds(200))));
        when(inbox.ultimoIdSolicitudPendiente("externo", "hold")).thenReturn(10L);
        when(inbox.buscarSolicitudesPendientes("externo", "hold", 0, 10, 100)).thenReturn(List.of());
        assertEquals(ESPERANDO_TELEFONO, servicio.confirmar(42L).estado());
        verify(inbox, times(1)).buscarSolicitudesPendientes(anyString(), anyString(), anyLong(), anyLong(), anyInt());
        verifyNoInteractions(correlacionar, remoto);
    }

    @Test void cursorSinAvanceNoProduceUnBucleInfinito() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora.plusSeconds(200))));
        when(inbox.ultimoIdSolicitudPendiente("externo", "hold")).thenReturn(10L);
        when(inbox.buscarSolicitudesPendientes("externo", "hold", 0, 10, 100)).thenReturn(List.of(0L));
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.CORRELACION_PENDIENTE, error.getMotivo());
        verifyNoInteractions(correlacionar, remoto);
    }

    @Test void rechazoPrevioPermiteReintentoExplicitoMientrasSigaVigente() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(CONFIRMACION_RECHAZADA, ahora.plusSeconds(300))));
        when(remoto.confirmar("hold", paciente)).thenReturn(new ConfirmacionInicialAceptada("externo", ahora.plusSeconds(200)));
        assertEquals(ESPERANDO_TELEFONO, servicio.confirmar(42L).estado());
    }
    @Test void falloDeLecturaNoEnviaPostNiExponeProcesoAjeno() {
        when(procesos.buscarPorId(42L)).thenThrow(new IllegalStateException());
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.PERSISTENCIA_FALLIDA, error.getMotivo());
        assertNull(error.getProcesoId());
        verifyNoInteractions(remoto);
    }

    @Test void holdQueVenceMientrasSeGuardaLaIntencionNoEnviaPost() {
        var reloj = mock(Clock.class);
        when(reloj.instant()).thenReturn(ahora, ahora, ahora.plusSeconds(300));
        var conDemora = new ConfirmarHoldService(usuario, procesos, remoto, inbox, correlacionar, reloj,
                new RecuperarConfirmacionService(inbox, procesos, reloj));
        var error = assertThrows(ConfirmarHoldException.class, () -> conDemora.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.HOLD_VENCIDO, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 200, 301})
    void respuesta202ConservaAceptacionPeroSoloDevuelveEsperaVigente(long segundos) {
        when(remoto.confirmar("hold", paciente)).thenReturn(new ConfirmacionInicialAceptada("externo", ahora.plusSeconds(segundos)));
        if (segundos > 0) {
            var resultado = servicio.confirmar(42L);
            assertEquals(ESPERANDO_TELEFONO, resultado.estado());
            assertEquals(ahora.plusSeconds(Math.min(segundos, 300)), resultado.vencimientoHold());
        } else {
            var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
            assertEquals(ConfirmarHoldException.Motivo.HOLD_VENCIDO, error.getMotivo());
        }
        verify(procesos).guardar(argThat(p -> p.estado() == ESPERANDO_TELEFONO
                && p.vencimientoHold().equals(ahora.plusSeconds(Math.min(segundos, 300)))));
        verify(remoto, times(1)).confirmar("hold", paciente);
    }

    @Test void aceptacionPersistidaQueYaVencioNoPermiteOtroPost() {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(ESPERANDO_TELEFONO, ahora)));
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.HOLD_VENCIDO, error.getMotivo());
        verifyNoInteractions(remoto);
        verify(procesos, never()).guardar(any());
    }

    @ParameterizedTest @EnumSource(value = ConfirmarHoldException.Motivo.class,
            names = {"CONFIGURACION_INCOMPLETA", "CREDENCIAL_TECNICA_RECHAZADA", "SOLICITUD_RECHAZADA"})
    void falloConocidoConservaHoldYAdmiteSoloReintentoExplicito(ConfirmarHoldException.Motivo motivo) {
        when(remoto.confirmar("hold", paciente)).thenThrow(new ConfirmarHoldException(motivo));
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        verify(procesos).guardar(argThat(p -> p.estado() == CONFIRMACION_RECHAZADA
                && p.vencimientoHold().equals(hold.vencimientoHold()) && p.impideNuevoHold(ahora)
                && p.puedeTransicionarA(CONFIRMACION_SOLICITADA)));
        verify(remoto, times(1)).confirmar("hold", paciente);
    }

    @Test void motivoInesperadoNoSeConvierteEnRechazoConocido() {
        when(remoto.confirmar("hold", paciente)).thenThrow(new ConfirmarHoldException(ConfirmarHoldException.Motivo.PERSISTENCIA_FALLIDA));
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        verify(procesos).guardar(argThat(p -> p.estado() == CONFIRMACION_INCIERTA));
    }

}
