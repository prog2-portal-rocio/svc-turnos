package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.CorrelacionarSolicitudInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.kafka.EventoReservaDecoder;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;

class RecepcionEventoReservaServiceTest {
    final InboxReservasPort inbox = mock(InboxReservasPort.class);
    final ProcesoReservaPersistencePort procesos = mock(ProcesoReservaPersistencePort.class);
    final CorrelacionarSolicitudInformacionUseCase correlacionar = mock(CorrelacionarSolicitudInformacionUseCase.class);
    final RecibirEventoReservaService recibir = new RecibirEventoReservaService(inbox, correlacionar);
    final RecepcionEventoReserva recepcion = new EventoReservaDecoder().decodificar(mensaje(solicitud("e", "p", "h"), "p", 0));

    @Test void conservaAntesDeIntentarCorrelacionar() {
        var original = new EventoInbox(1L, recepcion.evento(), EstadoEventoReserva.RECIBIDO, null, null);
        var pendiente = new EventoInbox(1L, recepcion.evento(), EstadoEventoReserva.PENDIENTE_CORRELACION, null, null);
        when(inbox.conservar(recepcion)).thenReturn(original);
        when(correlacionar.correlacionar(1L)).thenReturn(pendiente);
        assertSame(pendiente, recibir.recibir(recepcion));
        var orden = inOrder(inbox, correlacionar);
        orden.verify(inbox).conservar(recepcion);
        orden.verify(correlacionar).correlacionar(1L);
    }

    @Test void duplicadoPuedeRecuperarLaCorrelacionDelOriginal() {
        var duplicado = new EventoInbox(2L, recepcion.evento(), EstadoEventoReserva.DUPLICADO, null, 1L);
        when(inbox.conservar(recepcion)).thenReturn(duplicado);
        assertSame(duplicado, recibir.recibir(recepcion));
        verify(correlacionar).correlacionar(1L);
    }

    @Test void noCorrelacionaUnaRecepcionInvalida() {
        var invalido = new EventoInbox(1L, null, EstadoEventoReserva.INVALIDO, null, null);
        when(inbox.conservar(recepcion)).thenReturn(invalido);
        assertSame(invalido, recibir.recibir(recepcion));
        verifyNoInteractions(correlacionar);
    }

    @Test void errorDeConservacionNoSeInterpretaComoRecepcionExitosa() {
        when(inbox.conservar(recepcion)).thenThrow(new IllegalStateException("fallo simulado"));
        assertThrows(IllegalStateException.class, () -> recibir.recibir(recepcion));
        verifyNoInteractions(correlacionar);
    }

    @Test void correlacionAdelantadaNoGuardaNiModificaElProceso() {
        var original = new EventoInbox(1L, recepcion.evento(), EstadoEventoReserva.RECIBIDO, null, null);
        var resultado = new EventoInbox(1L, recepcion.evento(), EstadoEventoReserva.PENDIENTE_CORRELACION, null, null);
        when(inbox.buscarPorId(1L)).thenReturn(Optional.of(original));
        when(procesos.buscarPorProcesoExternoId("p")).thenReturn(List.of());
        when(inbox.registrarCorrelacion(eq(1L), any())).thenReturn(resultado);
        var servicio = new CorrelacionarSolicitudInformacionService(inbox, procesos,
                mock(com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase.class),
                mock(RecuperarConfirmacionService.class));
        assertSame(resultado, servicio.correlacionar(1L));
        verify(procesos, never()).guardar(any());
    }

    @Test void eventoDeOtroTipoNoConsultaNiActualizaReservas() {
        var otro = new EventoReserva("e", "EventoFuturo", "p", "h", recepcion.evento().occurredAt(), 1, null);
        when(inbox.buscarPorId(1L)).thenReturn(Optional.of(new EventoInbox(1L, otro, EstadoEventoReserva.RECIBIDO, null, null)));
        when(inbox.registrarCorrelacion(eq(1L), any())).thenReturn(
                new EventoInbox(1L, otro, EstadoEventoReserva.PENDIENTE_TIPO, null, null));
        var servicio = new CorrelacionarSolicitudInformacionService(inbox, procesos,
                mock(com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase.class),
                mock(RecuperarConfirmacionService.class));
        servicio.correlacionar(1L);
        verifyNoInteractions(procesos);
        verify(inbox).registrarCorrelacion(1L, new CorrelacionEvento(EstadoEventoReserva.PENDIENTE_TIPO, null));
    }
}
