package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.domain.model.EstadoProcesoReserva.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

/** Componentes reales con puertos aislados; ninguna conexión a PostgreSQL, Kafka o cátedra. */
class RecuperacionConfirmacionTest {
    final Clock clock = Clock.fixed(AHORA, ZoneOffset.UTC);
    final UsuarioAutenticadoPort usuario = mock(UsuarioAutenticadoPort.class);
    final ProcesoReservaPersistencePort procesos = mock(ProcesoReservaPersistencePort.class);
    final InboxReservasPort inbox = mock(InboxReservasPort.class);
    final ConfirmarHoldRemotoPort remoto = mock(ConfirmarHoldRemotoPort.class);
    final OutboxInformacionPort outbox = mock(OutboxInformacionPort.class);
    final AtomicReference<ProcesoReserva> almacen = new AtomicReference<>();
    final Map<Long, EventoInbox> mensajes = new HashMap<>();
    final AtomicBoolean fallarAceptacion = new AtomicBoolean();
    final PacienteReserva paciente = new PacienteReserva(7L, "Juan", "Perez");
    final RecuperarConfirmacionService recuperacion = new RecuperarConfirmacionService(inbox, procesos, clock);
    final CorrelacionarSolicitudInformacionService correlacionar = new CorrelacionarSolicitudInformacionService(
            inbox, procesos, mock(ProcesarResultadoReservaUseCase.class), recuperacion);
    final RecibirEventoReservaService recibir = new RecibirEventoReservaService(inbox, correlacionar);

    ConfirmarHoldService confirmador() {
        // Una instancia nueva no conserva información del intento REST anterior.
        return new ConfirmarHoldService(usuario, procesos, remoto, inbox, correlacionar, clock,
                new RecuperarConfirmacionService(inbox, procesos, clock));
    }

    @BeforeEach void preparar() {
        almacen.set(conEstado(HOLD_REGISTRADO));
        when(usuario.obtenerPaciente()).thenReturn(paciente);
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(procesos.buscarPorId(42L)).thenAnswer(invocation -> Optional.of(almacen.get()));
        when(procesos.buscarPorProcesoExternoId("externo")).thenAnswer(invocation -> List.of(almacen.get()));
        when(procesos.guardar(any())).thenAnswer(invocation -> {
            ProcesoReserva siguiente = invocation.getArgument(0);
            if (siguiente.estado() == ESPERANDO_TELEFONO && fallarAceptacion.getAndSet(false)) {
                throw new IllegalStateException("Persistencia simulada no disponible");
            }
            if (!siguiente.version().equals(almacen.get().version())) { throw new ProcesoReservaConcurrenteException(); }
            assertTrue(almacen.get().admiteProgreso(siguiente));
            var guardado = new ProcesoReserva(siguiente.id(), siguiente.usuarioId(), siguiente.profesionalId(), siguiente.fecha(),
                    siguiente.hora(), siguiente.estado(), siguiente.holdId(), siguiente.procesoExternoId(), siguiente.vencimientoHold(),
                    siguiente.creadoEn(), siguiente.actualizadoEn(), siguiente.version() + 1, siguiente.reservaId(), siguiente.resultadoOcurridoEn());
            almacen.set(guardado);
            return guardado;
        });
        when(inbox.buscarPorId(anyLong())).thenAnswer(invocation -> Optional.ofNullable(mensajes.get(invocation.getArgument(0))));
        when(inbox.buscarSolicitudesPorProceso(42L)).thenAnswer(invocation -> mensajes.values().stream()
                .filter(m -> m.estado() == EstadoEventoReserva.SOLICITUD_REGISTRADA).toList());
        when(inbox.ultimoIdSolicitudPendiente(anyString(), anyString())).thenAnswer(invocation -> mensajes.values().stream()
                .filter(m -> pendientePara(m, invocation.getArgument(0), invocation.getArgument(1)))
                .mapToLong(EventoInbox::id).max().orElse(0));
        when(inbox.buscarSolicitudesPendientes(anyString(), anyString(), anyLong(), anyLong(), anyInt())).thenAnswer(invocation -> {
            long desde = invocation.getArgument(2);
            long hasta = invocation.getArgument(3);
            int limite = invocation.getArgument(4);
            return mensajes.values().stream().filter(m -> pendientePara(m, invocation.getArgument(0), invocation.getArgument(1)))
                    .map(EventoInbox::id).filter(id -> id > desde && id <= hasta).sorted().limit(limite).toList();
        });
        when(inbox.registrarCorrelacion(anyLong(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            CorrelacionEvento correlacion = invocation.getArgument(1);
            var actualizado = new EventoInbox(id, mensajes.get(id).evento(), correlacion.estado(), correlacion.procesoLocalId(), null);
            mensajes.put(id, actualizado);
            return actualizado;
        });
        when(inbox.conservar(any())).thenAnswer(invocation -> mensajes.get(10L));
    }

    ProcesoReserva conEstado(EstadoProcesoReserva estado) {
        var base = proceso();
        return new ProcesoReserva(base.id(), base.usuarioId(), base.profesionalId(), base.fecha(), base.hora(), estado,
                base.holdId(), base.procesoExternoId(), base.vencimientoHold(), AHORA.minusSeconds(60), AHORA, 1L);
    }

    boolean pendientePara(EventoInbox mensaje, String externo, String hold) {
        var evento = mensaje.evento();
        return mensaje.pendienteDeCorrelacion() && mensaje.originalId() == null && evento != null
                && evento.esSolicitudInformacion() && externo.equals(evento.reservationProcessId()) && hold.equals(evento.holdId());
    }

    EventoInbox pendiente(Long id, String externo, String hold) {
        var base = solicitud().evento();
        return new EventoInbox(id, new EventoReserva("request-" + id, base.eventType(), externo, hold, base.occurredAt(), 1, base.solicitud()),
                EstadoEventoReserva.PENDIENTE_CORRELACION, null, null);
    }

    EventoInbox recibirSolicitud() {
        mensajes.put(10L, new EventoInbox(10L, solicitud().evento(), EstadoEventoReserva.RECIBIDO, null, null));
        return recibir.recibir(mock(RecepcionEventoReserva.class));
    }

    @Test void perdidaDe202SeRecuperaConKafkaYPermiteTelefonoSinOtroPost() {
        when(remoto.confirmar(any(), any())).thenThrow(new ConfirmarHoldException(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO));
        var servicio = confirmador();
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(CONFIRMACION_INCIERTA, almacen.get().estado());
        var recibido = recibirSolicitud();
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, recibido.estado());
        assertEquals(ESPERANDO_TELEFONO, almacen.get().estado());
        assertEquals(solicitud().evento().solicitud().expiresAt(), almacen.get().vencimientoHold());
        assertNull(almacen.get().reservaId());
        when(outbox.registrar(eq(42L), eq(7L), eq(10L), any(), any())).thenAnswer(invocation -> {
            assertEquals(almacen.get().vencimientoHold(), SolicitudEnvioInformacion.validar(almacen.get(), 7L, recibido, AHORA));
            return envio(EstadoEnvioInformacion.PENDIENTE);
        });
        var enviar = new EnviarInformacionService(usuario, procesos, inbox, outbox, true, GRUPO, TOPIC);
        assertEquals(EstadoEnvioInformacion.PENDIENTE, enviar.enviar(42L, NUMERO).estado());
        verify(remoto, times(1)).confirmar("hold", paciente);
    }

    @Test void falloPosteriorAl202ConservaIntencionYKafkaRecupera() {
        fallarAceptacion.set(true);
        when(remoto.confirmar(any(), any())).thenReturn(new ConfirmacionInicialAceptada("externo", AHORA.plusSeconds(200)));
        var servicio = confirmador();
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.PERSISTENCIA_FALLIDA, error.getMotivo());
        assertEquals(CONFIRMACION_SOLICITADA, almacen.get().estado());
        recibirSolicitud();
        assertEquals(ESPERANDO_TELEFONO, confirmador().confirmar(42L).estado());
        verify(remoto, times(1)).confirmar("hold", paciente);
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class, names = {"CONFIRMACION_INCIERTA", "CONFIRMACION_SOLICITADA"})
    void reinicioRecuperaSoloDesdeInboxDurable(EstadoProcesoReserva estado) {
        almacen.set(conEstado(estado));
        mensajes.put(10L, solicitud());
        var recuperado = confirmador().confirmar(42L);
        assertEquals(ESPERANDO_TELEFONO, recuperado.estado());
        assertEquals(recuperado, confirmador().confirmar(42L));
        verify(procesos, times(1)).guardar(any());
        verifyNoInteractions(remoto);
    }

    @Test void evidenciaKafkaDurantePostNoProvocaEscrituraObsoletaNiOtroEnvio() {
        when(remoto.confirmar(any(), any())).thenAnswer(invocation -> {
            recibirSolicitud();
            assertEquals(ESPERANDO_TELEFONO, almacen.get().estado());
            return new ConfirmacionInicialAceptada("externo", AHORA.plusSeconds(200));
        });
        assertEquals(ESPERANDO_TELEFONO, confirmador().confirmar(42L).estado());
        verify(remoto, times(1)).confirmar("hold", paciente);
    }

    @Test void escrituraFallidaTrasCorrelacionSeRecuperaConReentregaMismaPosicion() {
        almacen.set(conEstado(CONFIRMACION_SOLICITADA));
        fallarAceptacion.set(true);
        assertThrows(IllegalStateException.class, this::recibirSolicitud);
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, mensajes.get(10L).estado());
        assertEquals(CONFIRMACION_SOLICITADA, almacen.get().estado());
        recibir.recibir(mock(RecepcionEventoReserva.class));
        assertEquals(ESPERANDO_TELEFONO, almacen.get().estado());
        var version = almacen.get().version();
        recibir.recibir(mock(RecepcionEventoReserva.class));
        assertEquals(version, almacen.get().version());
        verifyNoInteractions(remoto);
    }

    @Test void sinEvidenciaConservaIncertidumbreYNoReenviaPost() {
        almacen.set(conEstado(CONFIRMACION_INCIERTA));
        var servicio = confirmador();
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(CONFIRMACION_INCIERTA, almacen.get().estado());
        verify(procesos, never()).guardar(any());
        verifyNoInteractions(remoto);
    }

    @Test void evidenciaAmbiguaNoSeEligeArbitrariamente() {
        almacen.set(conEstado(CONFIRMACION_INCIERTA));
        when(inbox.buscarSolicitudesPorProceso(42L)).thenReturn(List.of(solicitud(), solicitud()));
        assertEquals(almacen.get(), recuperacion.recuperar(almacen.get()));
        verify(procesos, never()).guardar(any());
        verifyNoInteractions(remoto);
    }

    @Test void propietarioAjenoNoRecuperaNiConsultaEvidencia() {
        almacen.set(conEstado(CONFIRMACION_INCIERTA));
        when(usuario.obtenerPaciente()).thenReturn(new PacienteReserva(8L, "Otra", "Persona"));
        var servicio = confirmador();
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(ConfirmarHoldException.Motivo.PROCESO_AJENO, error.getMotivo());
        verifyNoInteractions(inbox, remoto);
        verify(procesos, never()).guardar(any());
    }

    @Test void resultadoDefinitivoConcurrenteNoEsSobrescrito() {
        var incierto = conEstado(CONFIRMACION_INCIERTA);
        mensajes.put(10L, solicitud());
        var confirmado = new ProcesoReserva(42L, 7L, 15L, incierto.fecha(), incierto.hora(), CONFIRMADA,
                "hold", "externo", incierto.vencimientoHold(), incierto.creadoEn(), AHORA, 2L, 99L, AHORA);
        doAnswer(invocation -> { almacen.set(confirmado); throw new ProcesoReservaConcurrenteException(); })
                .when(procesos).guardar(any());
        assertEquals(confirmado, recuperacion.recuperar(incierto));
        assertEquals(CONFIRMADA, almacen.get().estado());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class,
            names = {"CONFIRMACION_INCIERTA", "CONFIRMACION_SOLICITADA", "ESPERANDO_TELEFONO"})
    void recuperaSolicitudAdelantadaAunqueHayaMasDeMilPendientesAjenos(EstadoProcesoReserva estado) {
        almacen.set(conEstado(estado));
        for (long id = 1; id <= 1100; id++) { mensajes.put(id, pendiente(id, "otro-" + id, "otro-hold")); }
        mensajes.put(1101L, pendiente(1101L, "externo", "hold-ajeno"));
        mensajes.put(1102L, pendiente(1102L, "externo-ajeno", "hold"));
        mensajes.put(1103L, pendiente(1103L, "externo", "hold"));
        var recuperado = confirmador().confirmar(42L);
        assertEquals(ESPERANDO_TELEFONO, recuperado.estado());
        var evidencia = mensajes.get(1103L);
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, evidencia.estado());
        assertEquals(42L, evidencia.procesoLocalId());
        assertEquals(evidencia.evento().solicitud().expiresAt(), SolicitudEnvioInformacion.validar(recuperado, 7L, evidencia, AHORA));
        assertEquals(1102, mensajes.values().stream().filter(EventoInbox::pendienteDeCorrelacion).count());
        verify(inbox).buscarSolicitudesPendientes("externo", "hold", 0, 1103, 100);
        verify(inbox, never()).registrarCorrelacion(eq(1101L), any());
        verify(inbox, never()).registrarCorrelacion(eq(1102L), any());
        verifyNoInteractions(remoto);
    }

    @Test void identidadAmbiguaNoSeRecuperaAunqueSeEncuentreLaSolicitud() {
        almacen.set(conEstado(CONFIRMACION_INCIERTA));
        mensajes.put(10L, pendiente(10L, "externo", "hold"));
        when(procesos.buscarPorProcesoExternoId("externo")).thenReturn(List.of(almacen.get(), almacen.get()));
        var servicio = confirmador();
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE, mensajes.get(10L).estado());
        assertEquals(CONFIRMACION_INCIERTA, almacen.get().estado());
        verify(procesos, never()).guardar(any());
        verifyNoInteractions(remoto);
    }

    @Test void solicitudPendienteVencidaNoRecuperaIncertidumbre() {
        almacen.set(conEstado(CONFIRMACION_INCIERTA));
        var base = pendiente(10L, "externo", "hold").evento();
        var vencido = new EventoReserva(base.eventId(), base.eventType(), base.reservationProcessId(), base.holdId(), AHORA, 1,
                new SolicitudInformacionAdicional("PHONE_NUMBER", "Ingrese telefono", AHORA));
        mensajes.put(10L, new EventoInbox(10L, vencido, EstadoEventoReserva.PENDIENTE_CORRELACION, null, null));
        var servicio = confirmador();
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(42L));
        assertEquals(CONFIRMACION_INCIERTA, almacen.get().estado());
        verify(procesos, never()).guardar(any());
        verifyNoInteractions(remoto);
    }
}
