package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.kafka.ResultadosReservaScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class RecuperarResultadosReservaServiceTest {
    final InboxReservasPort inbox=mock(InboxReservasPort.class);
    final ProcesarResultadoReservaUseCase resultados=mock(ProcesarResultadoReservaUseCase.class);
    final RecuperarResultadosReservaService servicio=new RecuperarResultadosReservaService(inbox,resultados);
    @Test void cursorCircularNoSeAtascaEnElPrimerProcesoDesconocido() {
        when(inbox.buscarResultadosPendientes(0,30,100)).thenReturn(List.of(10L,20L));
        assertEquals(20,servicio.recuperar(0,30,100));verify(resultados).procesar(10L);verify(resultados).procesar(20L);
        when(inbox.buscarResultadosPendientes(20,30,100)).thenReturn(List.of());assertEquals(0,servicio.recuperar(20,30,100));
    }
    @Test void errorIndividualNoBloqueaElLoteNiSePresentaComoExito(CapturedOutput salida) {
        when(inbox.buscarResultadosPendientes(0,30,100)).thenReturn(List.of(10L,20L));
        when(resultados.procesar(10L)).thenThrow(new IllegalStateException("detalle-no-publicable"));
        assertEquals(20,servicio.recuperar(0,30,100));
        verify(resultados).procesar(20L);
        verify(inbox,never()).registrarCorrelacion(any(),any());
        assertTrue(salida.getOut().contains("permanece pendiente en la inbox: recepcion=10"));
        assertFalse(salida.getAll().contains("detalle-no-publicable"));
    }
    @Test void falloDeLecturaDelLoteSePropagaSinProcesarEventos() {
        when(inbox.buscarResultadosPendientes(0,30,100)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> servicio.recuperar(0,30,100));
        verifyNoInteractions(resultados);
    }
    @Test void schedulerConservaCursorSiFallaLaBaseSinDescartarEventos() {
        var caso=mock(RecuperarResultadosReservaService.class);
        var scheduler=new ResultadosReservaScheduler(caso);
        when(caso.ultimoIdPendiente()).thenReturn(30L);
        when(caso.recuperar(0,30,100)).thenThrow(new IllegalStateException()).thenReturn(20L);
        assertDoesNotThrow(scheduler::recuperar);scheduler.recuperar();scheduler.recuperar();
        verify(caso,times(2)).recuperar(0,30,100);verify(caso).recuperar(20,30,100);
        verify(caso,times(1)).ultimoIdPendiente();
    }
    @Test void falloParcialQuedaDisponibleParaUnaInstanciaNueva() {
        var pendientes=new HashSet<>(Set.of(10L,20L));
        var primerIntento=new AtomicBoolean(true);
        when(inbox.buscarResultadosPendientes(anyLong(),eq(20L),eq(100))).thenAnswer(invocation -> {
            long desde=invocation.getArgument(0);
            return pendientes.stream().filter(id -> id>desde).sorted().toList();
        });
        when(resultados.procesar(anyLong())).thenAnswer(invocation -> {
            Long id=invocation.getArgument(0);
            if (id.equals(10L) && primerIntento.getAndSet(false)) { throw new IllegalStateException(); }
            pendientes.remove(id);
            return null;
        });
        assertEquals(0,servicio.recuperar(0,20,100));
        assertEquals(Set.of(10L),pendientes);
        var reiniciado=new RecuperarResultadosReservaService(inbox,resultados);
        assertEquals(10,reiniciado.recuperar(0,20,100));
        assertEquals(0,reiniciado.recuperar(10,20,100));
        assertTrue(pendientes.isEmpty());
        verify(resultados,times(2)).procesar(10L);
        verify(resultados,times(1)).procesar(20L);
    }
    @Test void masDeMilEventosSeRecorrenEnLotesSinOcultarLosUltimos() {
        when(inbox.ultimoIdResultadoPendiente()).thenReturn(1205L);
        when(inbox.buscarResultadosPendientes(anyLong(),eq(1205L),eq(100))).thenAnswer(invocation -> {
            long desde=invocation.getArgument(0);
            return LongStream.rangeClosed(desde+1,Math.min(desde+100,1205)).boxed().toList();
        });
        long hasta=servicio.ultimoIdPendiente();
        long cursor=0;
        for (int lote=0;lote<13;lote++) { cursor=servicio.recuperar(cursor,hasta,100); }
        assertEquals(0,cursor);
        var ids=org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(resultados,times(1205)).procesar(ids.capture());
        assertEquals(LongStream.rangeClosed(1,1205).boxed().toList(),ids.getAllValues());
    }
    @Test void techoFijoPermiteVolverALosFallidosAunqueSiganLlegandoEventos() {
        var caso=mock(RecuperarResultadosReservaService.class);
        when(caso.ultimoIdPendiente()).thenReturn(20L,40L);
        when(caso.recuperar(0,20,100)).thenReturn(10L);
        var scheduler=new ResultadosReservaScheduler(caso);
        scheduler.recuperar();scheduler.recuperar();scheduler.recuperar();
        verify(caso,times(2)).ultimoIdPendiente();
        verify(caso).recuperar(0,20,100);
        verify(caso).recuperar(10,20,100);
        verify(caso).recuperar(0,40,100);
    }
    @Test void reiniciarSchedulerComienzaDesdeCeroSinNecesitarReentregasKafka() {
        var caso=mock(RecuperarResultadosReservaService.class);
        when(caso.ultimoIdPendiente()).thenReturn(30L);
        when(caso.recuperar(0,30,100)).thenReturn(20L);
        new ResultadosReservaScheduler(caso).recuperar();
        new ResultadosReservaScheduler(caso).recuperar();
        verify(caso,times(2)).recuperar(0,30,100);
    }
    @Test void falloAlConsultarTechoSeReintentaEnLaProximaEjecucion() {
        var caso=mock(RecuperarResultadosReservaService.class);
        when(caso.ultimoIdPendiente()).thenThrow(new IllegalStateException()).thenReturn(30L);
        var scheduler=new ResultadosReservaScheduler(caso);
        assertDoesNotThrow(scheduler::recuperar);
        scheduler.recuperar();
        verify(caso).recuperar(0,30,100);
    }
}
