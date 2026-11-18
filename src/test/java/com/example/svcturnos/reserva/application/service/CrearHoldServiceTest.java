package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.domain.exception.CrearHoldException.Motivo.*;

@ExtendWith(MockitoExtension.class)
class CrearHoldServiceTest {
    @Mock UsuarioAutenticadoPort usuario;
    @Mock ConsultarDisponibilidadUseCase disponibilidad;
    @Mock CrearHoldRemotoPort remoto;
    @Mock ProcesoReservaPersistencePort procesos;
    CrearHoldService servicio;
    final SolicitudHold solicitud = new SolicitudHold(15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30));
    // Fixture vigente respecto de un reloj fijo; el servicio delega la política temporal al puerto.
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-18T14:55:00Z"), ZoneOffset.UTC);
    final Instant vence = RELOJ.instant().plusSeconds(300);

    @BeforeEach
    void preparar() { servicio = new CrearHoldService(usuario, disponibilidad, remoto, procesos); }

    void agenda() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(disponibilidad.consultar(15L, solicitud.date())).thenReturn(List.of(
                new BloqueHorario(solicitud.date(), solicitud.startTime(), LocalTime.of(10, 0))));
    }

    void persistencia() {
        when(procesos.guardar(any())).thenAnswer(invocation -> {
            ProcesoReserva p = invocation.getArgument(0);
            return new ProcesoReserva(1L, p.usuarioId(), p.profesionalId(), p.fecha(), p.hora(), p.estado(),
                    p.holdId(), p.procesoExternoId(), p.vencimientoHold(), p.creadoEn(), p.actualizadoEn(),
                    p.version() == null ? 0L : p.version() + 1);
        });
    }

    @Test
    void guardaIntencionAntesDeUnSoloPostYRegistraHoldSinConfirmarReserva() {
        agenda(); persistencia();
        when(remoto.crear(solicitud, "user-7")).thenReturn(new HoldCreado("hold", "externo", vence));
        var resultado = servicio.crear(solicitud);
        assertEquals(7L, resultado.usuarioId());
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, resultado.estado());
        assertEquals("hold", resultado.holdId());
        assertEquals("externo", resultado.procesoExternoId());
        assertEquals(vence, resultado.vencimientoHold());
        var orden = inOrder(procesos, remoto);
        orden.verify(procesos).guardar(argThat(p -> p.estado() == EstadoProcesoReserva.HOLD_SOLICITADO));
        orden.verify(remoto).crear(solicitud, "user-7");
        orden.verify(procesos).guardar(argThat(p -> p.estado() == EstadoProcesoReserva.HOLD_REGISTRADO));
        orden.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = CrearHoldException.Motivo.class, names = {"CONFLICTO", "SOLICITUD_INVALIDA",
            "CREDENCIAL_TECNICA_RECHAZADA", "PROFESIONAL_INEXISTENTE", "CONFIGURACION_INCOMPLETA"})
    void rechazoDefinitivoQuedaPersistidoSinReintento(CrearHoldException.Motivo motivo) {
        agenda(); persistencia();
        when(remoto.crear(solicitud, "user-7")).thenThrow(new CrearHoldException(motivo));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(motivo, error.getMotivo());
        assertEquals(1L, error.getProcesoId());
        verify(procesos).guardar(argThat(p -> p.estado() == EstadoProcesoReserva.HOLD_RECHAZADO));
        verify(remoto, times(1)).crear(solicitud, "user-7");
    }

    @Test
    void timeoutNoSeMarcaComoRechazoNiSeReenviaElPost() {
        agenda(); persistencia();
        when(remoto.crear(solicitud, "user-7")).thenThrow(new CrearHoldException(RESULTADO_INCIERTO));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(RESULTADO_INCIERTO, error.getMotivo());
        assertEquals(1L, error.getProcesoId());
        verify(procesos, times(1)).guardar(any());
        verify(remoto, times(1)).crear(solicitud, "user-7");
    }

    @Test
    void falloLocalAntesDelPostNoGeneraEfectosExternos() {
        agenda();
        when(procesos.guardar(any())).thenThrow(new IllegalStateException("fallo simulado"));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(PERSISTENCIA_FALLIDA, error.getMotivo());
        verifyNoInteractions(remoto);
    }

    @Test
    void falloAlGuardarResultadoMantieneLaIdentidadDelIntentoYNoRepitePost() {
        agenda();
        var intento = ProcesoReserva.crear(7L, 15L, solicitud.date(), solicitud.startTime()).solicitarHold();
        var guardado = new ProcesoReserva(1L, 7L, 15L, intento.fecha(), intento.hora(), intento.estado(),
                null, null, null, intento.creadoEn(), intento.actualizadoEn(), 0L);
        when(procesos.guardar(any())).thenReturn(guardado).thenThrow(new IllegalStateException("fallo"));
        when(remoto.crear(solicitud, "user-7")).thenReturn(new HoldCreado("hold", "externo", vence));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(PERSISTENCIA_FALLIDA, error.getMotivo());
        assertEquals(1L, error.getProcesoId());
        verify(remoto, times(1)).crear(solicitud, "user-7");
    }

    @Test
    void sinDisponibilidadNoGuardaNiCreaHold() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(disponibilidad.consultar(15L, solicitud.date())).thenReturn(List.of());
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(SLOT_NO_DISPONIBLE, error.getMotivo());
        verifyNoInteractions(procesos, remoto);
    }

    @Test
    void usuarioNoAutorizadoNoConsultaDependencias() {
        when(usuario.obtenerUsuarioId()).thenThrow(new CrearHoldException(NO_AUTORIZADO));
        assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        verifyNoInteractions(disponibilidad, procesos, remoto);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void falloInesperadoORespuestaNulaMantienenIncertidumbre(boolean excepcion) {
        agenda(); persistencia();
        if (excepcion) {
            when(remoto.crear(any(), anyString())).thenThrow(new IllegalStateException("fallo inesperado"));
        }
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(RESULTADO_INCIERTO, error.getMotivo());
        assertEquals(1L, error.getProcesoId());
        verify(procesos, times(1)).guardar(any());
        verify(remoto, times(1)).crear(solicitud, "user-7");
    }
}
