package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.kafka.ResultadosReservaScheduler;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RecuperarResultadosReservaServiceTest {
    final InboxReservasPort inbox=mock(InboxReservasPort.class);
    final ProcesarResultadoReservaUseCase resultados=mock(ProcesarResultadoReservaUseCase.class);
    final RecuperarResultadosReservaService servicio=new RecuperarResultadosReservaService(inbox,resultados);
    @Test void cursorCircularNoSeAtascaEnElPrimerProcesoDesconocido() {
        when(inbox.buscarResultadosPendientes(0,100)).thenReturn(List.of(10L,20L));
        assertEquals(20,servicio.recuperar(0,100));verify(resultados).procesar(10L);verify(resultados).procesar(20L);
        when(inbox.buscarResultadosPendientes(20,100)).thenReturn(List.of());assertEquals(0,servicio.recuperar(20,100));
    }
    @Test void errorNoSePresentaComoLoteCompletado() {
        when(inbox.buscarResultadosPendientes(0,100)).thenReturn(List.of(10L));
        when(resultados.procesar(10L)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> servicio.recuperar(0,100));
    }
    @Test void schedulerConservaCursorSiFallaLaBaseSinDescartarEventos() {
        var caso=mock(RecuperarResultadosReservaService.class);
        var scheduler=new ResultadosReservaScheduler(caso);
        when(caso.recuperar(0,100)).thenThrow(new IllegalStateException()).thenReturn(20L);
        assertDoesNotThrow(scheduler::recuperar);scheduler.recuperar();scheduler.recuperar();
        verify(caso,times(2)).recuperar(0,100);verify(caso).recuperar(20,100);
    }
}
