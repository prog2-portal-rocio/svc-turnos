package com.example.svcturnos.disponibilidad.infrastructure.web;

import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.disponibilidad.infrastructure.web.controller.DisponibilidadController;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisponibilidadControllerTest {
    final ConsultarDisponibilidadUseCase casoDeUso = mock(ConsultarDisponibilidadUseCase.class);
    final DisponibilidadController controller = new DisponibilidadController(casoDeUso);
    static final LocalDate FECHA = LocalDate.of(2026,12,1);

    @Test void delegaSinReordenarNiRecalcularLosBloquesDelCasoDeUso() {
        var primero = new BloqueHorario(FECHA, LocalTime.of(9,0), LocalTime.of(9,30));
        var segundo = new BloqueHorario(FECHA, LocalTime.of(11,0), LocalTime.of(11,45));
        when(casoDeUso.consultar(15L, FECHA)).thenReturn(List.of(primero, segundo));
        var respuesta = controller.consultar(15L, FECHA);
        assertEquals(2, respuesta.size());
        assertEquals(primero.fecha(), respuesta.getFirst().fecha());
        assertEquals(primero.inicio(), respuesta.getFirst().inicio());
        assertEquals(segundo.inicio(), respuesta.getLast().inicio());
        assertEquals(segundo.fin(), respuesta.getLast().fin());
        verify(casoDeUso).consultar(15L, FECHA);
        verifyNoMoreInteractions(casoDeUso);
    }

    @Test void sinBloquesDevuelveListaVacia() {
        when(casoDeUso.consultar(15L, FECHA)).thenReturn(List.of());
        assertTrue(controller.consultar(15L, FECHA).isEmpty());
    }

    @Test void identificadorNoPositivoNoInvocaCasoDeUso() {
        assertThrows(IllegalArgumentException.class, () -> controller.consultar(0L, FECHA));
        verifyNoInteractions(casoDeUso);
    }
}
