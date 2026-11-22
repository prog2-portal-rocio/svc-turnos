package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.ports.in.ConsultarReservasUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CancelarReservaServiceTest {
    final UsuarioAutenticadoPort usuario = mock(UsuarioAutenticadoPort.class);
    final ConsultarReservasUseCase consultas = mock(ConsultarReservasUseCase.class);
    final CancelacionReservaPersistencePort store = mock(CancelacionReservaPersistencePort.class);
    final CancelarReservaRemotaPort remoto = mock(CancelarReservaRemotaPort.class);
    final CancelarReservaService service = new CancelarReservaService(usuario, consultas, store, remoto);
    static final Instant AHORA = Instant.parse("2026-11-21T10:00:00Z");
    final SolicitudCancelacion solicitud = new SolicitudCancelacion("externo", null);
    ReservaCentral reserva(EstadoReservaCentral estado, String paciente) {
        return new ReservaCentral(8251L, "externo", paciente, LocalDate.of(2026,12,1), LocalTime.of(9,0),LocalTime.of(9,30),
                15L,"Ana","Gomez",estado,AHORA,AHORA,estado == EstadoReservaCentral.CANCELLED ? AHORA : null);
    }
    CancelacionReserva intento(EstadoCancelacion estado, Long propietario) {
        return new CancelacionReserva("externo",propietario,8251L,estado,AHORA,AHORA,
                estado == EstadoCancelacion.CANCELADA ? AHORA : null,false);
    }
    @BeforeEach void preparar() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(store.buscar("externo")).thenReturn(Optional.empty());
        doAnswer(call -> {
            CriteriosConsultaReservas filtros = call.getArgument(0);
            return new PaginaReservas(List.of(),0,filtros.page(),filtros.size());
        }).when(consultas).consultarReservas(any());
    }
    void propia(EstadoReservaCentral estado) {
        doAnswer(call -> {
            CriteriosConsultaReservas filtros = call.getArgument(0);
            return filtros.status() == estado ? new PaginaReservas(List.of(reserva(estado,"user-7")),1,0,100)
                    : new PaginaReservas(List.of(),0,0,100);
        }).when(consultas).consultarReservas(any());
    }
    @Test void propiaSinProcesoLocalPuedeCancelarseYPrimeroPersisteIntencion() {
        propia(EstadoReservaCentral.CONFIRMED);
        var resultado = new CancelacionConfirmada(8251L,"externo",AHORA);
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.SOLICITADA,7L),true));
        when(remoto.cancelar(solicitud,8251L)).thenReturn(resultado);
        when(store.confirmar(7L,resultado)).thenReturn(intento(EstadoCancelacion.CANCELADA,7L));
        assertEquals(EstadoCancelacion.CANCELADA,service.cancelar(solicitud).estado());
        var orden = inOrder(store,remoto); orden.verify(store).buscar("externo");
        orden.verify(store).preparar(eq(7L),any()); orden.verify(remoto).cancelar(solicitud,8251L);
        orden.verify(store).confirmar(7L,resultado);
    }
    @Test void inexistenteOAjenaNoEnviaPost() {
        assertEquals(CancelarReservaException.Motivo.RESERVA_NO_ENCONTRADA,
                assertThrows(CancelarReservaException.class, () -> service.cancelar(solicitud)).getMotivo());
        verifyNoInteractions(remoto); verify(store,never()).preparar(any(),any());
    }
    @Test void pacienteInconsistenteNoEnviaPost() {
        doReturn(new PaginaReservas(List.of(reserva(EstadoReservaCentral.CONFIRMED,"user-8")),1,0,100)).when(consultas).consultarReservas(any());
        assertThrows(CancelarReservaException.class, () -> service.cancelar(solicitud)); verifyNoInteractions(remoto);
    }
    @Test void intentoAjenoNoConsultaNiEnvia() {
        when(store.buscar("externo")).thenReturn(Optional.of(intento(EstadoCancelacion.INCIERTA,8L)));
        assertThrows(CancelarReservaException.class, () -> service.cancelar(solicitud)); verifyNoInteractions(consultas,remoto);
    }
    @ParameterizedTest @EnumSource(value=EstadoCancelacion.class,names={"SOLICITADA","INCIERTA","CANCELADA"})
    void repetidaNoReenvia(EstadoCancelacion estado) {
        propia(EstadoReservaCentral.CONFIRMED);
        when(store.buscar("externo")).thenReturn(Optional.of(intento(estado,7L)));
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(estado,7L),false));
        assertEquals(estado,service.cancelar(solicitud).estado()); verifyNoInteractions(remoto);
    }
    @Test void centralYaCanceladaResuelveIncertidumbreSinPost() {
        propia(EstadoReservaCentral.CANCELLED);
        when(store.buscar("externo")).thenReturn(Optional.of(intento(EstadoCancelacion.INCIERTA,7L)));
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.CANCELADA,7L),false));
        assertEquals(EstadoCancelacion.CANCELADA,service.cancelar(solicitud).estado()); verifyNoInteractions(remoto);
    }
    @ParameterizedTest @EnumSource(value=EstadoReservaCentral.class,names={"PHONE_PENDING","FAILED"})
    void estadoNoCancelable(EstadoReservaCentral estado) {
        propia(estado); assertThrows(CancelarReservaException.class, () -> service.cancelar(solicitud)); verifyNoInteractions(remoto);
    }
    @ParameterizedTest @EnumSource(CancelarReservaException.Motivo.class)
    void registraFalloSinRepetirPost(CancelarReservaException.Motivo motivo) {
        propia(EstadoReservaCentral.CONFIRMED);
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.SOLICITADA,7L),true));
        var error = new CancelarReservaException(motivo);
        var estado = error.resultadoIncierto() ? EstadoCancelacion.INCIERTA : EstadoCancelacion.RECHAZADA;
        when(remoto.cancelar(solicitud,8251L)).thenThrow(error);
        when(store.registrarFallo(7L,"externo",estado)).thenReturn(intento(estado,7L));
        assertSame(error,assertThrows(CancelarReservaException.class, () -> service.cancelar(solicitud)));
        verify(remoto,times(1)).cancelar(solicitud,8251L);
    }
    @Test void consultaFallidaNuncaEjecutaPost() {
        doThrow(new com.example.svcturnos.reserva.domain.exception.ConsultaReservasException(
                com.example.svcturnos.reserva.domain.exception.ConsultaReservasException.Motivo.TIMEOUT))
                .when(consultas).consultarReservas(any());
        assertEquals(CancelarReservaException.Motivo.TIMEOUT,
                assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)).getMotivo());
        verifyNoInteractions(remoto);verify(store,never()).preparar(any(),any());
    }
    @Test void falloDelDiagnosticoNoReemplazaResultadoRemotoIncierto() {
        propia(EstadoReservaCentral.CONFIRMED);
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.SOLICITADA,7L),true));
        var error=new CancelarReservaException(CancelarReservaException.Motivo.RESULTADO_INCIERTO);
        when(remoto.cancelar(solicitud,8251L)).thenThrow(error);
        when(store.registrarFallo(7L,"externo",EstadoCancelacion.INCIERTA))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("sensible"));
        assertSame(error,assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)));
    }
    @Test void kafkaPuedeHaberCanceladoMientrasSeEsperabaRespuestaRest() {
        propia(EstadoReservaCentral.CONFIRMED);
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.SOLICITADA,7L),true));
        when(remoto.cancelar(solicitud,8251L)).thenThrow(new CancelarReservaException(CancelarReservaException.Motivo.RESULTADO_INCIERTO));
        when(store.registrarFallo(7L,"externo",EstadoCancelacion.INCIERTA)).thenReturn(intento(EstadoCancelacion.CANCELADA,7L));
        assertEquals(EstadoCancelacion.CANCELADA,service.cancelar(solicitud).estado());
    }

    @Test void persistenciaFallidaDespuesDeExitoNoVuelveAEjecutarPost() {
        propia(EstadoReservaCentral.CONFIRMED);
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.SOLICITADA,7L),true));
        var resultado=new CancelacionConfirmada(8251L,"externo",AHORA);
        when(remoto.cancelar(solicitud,8251L)).thenReturn(resultado);
        when(store.confirmar(7L,resultado)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("sensible"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->service.cancelar(solicitud));
        verify(remoto,times(1)).cancelar(solicitud,8251L);
        verify(store,never()).registrarFallo(any(),any(),any());
    }
    @Test void recorridoIncompletoNoDeclaraAusenciaNiEjecutaPost() {
        doAnswer(call->{CriteriosConsultaReservas filtros=call.getArgument(0);
            return new PaginaReservas(paginaCompleta(filtros.page()),2000,filtros.page(),filtros.size());
        }).when(consultas).consultarReservas(any());
        assertEquals(CancelarReservaException.Motivo.VERIFICACION_INCOMPLETA,
                assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)).getMotivo());
        verify(consultas,times(10)).consultarReservas(any());verifyNoInteractions(remoto);
    }

    List<ReservaCentral> paginaCompleta(int pagina) {
        return java.util.stream.IntStream.range(0,100).mapToObj(numero -> {
            long id=(long)pagina*100+numero+1;
            return new ReservaCentral(id,"otro-"+id,"user-7",LocalDate.of(2026,12,1),LocalTime.of(9,0),LocalTime.of(9,30),
                    15L,"Ana","Gomez",EstadoReservaCentral.CONFIRMED,AHORA,AHORA,null);
        }).toList();
    }
    @Test void paginaVaciaConTotalPendienteNoSeInterpretaComoAusencia() {
        doReturn(new PaginaReservas(List.of(),1,0,100)).when(consultas).consultarReservas(any());
        assertEquals(CancelarReservaException.Motivo.VERIFICACION_INCOMPLETA,
                assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)).getMotivo());
        verifyNoInteractions(remoto);verify(store,never()).preparar(any(),any());
    }
    @Test void paginaTruncadaNoAutorizaPostAunqueIncluyaLaReservaBuscada() {
        doReturn(new PaginaReservas(List.of(reserva(EstadoReservaCentral.CONFIRMED,"user-7")),150,0,100))
                .when(consultas).consultarReservas(any());
        assertEquals(CancelarReservaException.Motivo.VERIFICACION_INCOMPLETA,
                assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)).getMotivo());
        verifyNoInteractions(remoto);verify(store,never()).preparar(any(),any());
    }
    @Test void totalCambianteNoPermiteContinuarComoSiLaBusquedaFueraCompleta() {
        doAnswer(call->{CriteriosConsultaReservas filtros=call.getArgument(0);
            return new PaginaReservas(paginaCompleta(filtros.page()),filtros.page()==0 ? 300 : 301,filtros.page(),100);
        }).when(consultas).consultarReservas(any());
        assertEquals(CancelarReservaException.Motivo.VERIFICACION_INCOMPLETA,
                assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)).getMotivo());
        verifyNoInteractions(remoto);verify(consultas,times(2)).consultarReservas(any());
    }
    @Test void milRegistrosExactosPermitenConcluirAusenciaTrasRecorrerTodosLosEstados() {
        doAnswer(call->{CriteriosConsultaReservas filtros=call.getArgument(0);
            return filtros.status()==EstadoReservaCentral.CONFIRMED
                    ? new PaginaReservas(paginaCompleta(filtros.page()),1000,filtros.page(),100)
                    : new PaginaReservas(List.of(),0,filtros.page(),100);
        }).when(consultas).consultarReservas(any());
        assertEquals(CancelarReservaException.Motivo.RESERVA_NO_ENCONTRADA,
                assertThrows(CancelarReservaException.class,()->service.cancelar(solicitud)).getMotivo());
        verify(consultas,times(13)).consultarReservas(any());verifyNoInteractions(remoto);
    }
    @Test void reservaEnElRegistroMilSeEncuentraSinConsultarUnaPaginaExtra() {
        doAnswer(call->{CriteriosConsultaReservas filtros=call.getArgument(0);
            var contenido=new java.util.ArrayList<>(paginaCompleta(filtros.page()));
            if(filtros.page()==9) { contenido.set(99,reserva(EstadoReservaCentral.CONFIRMED,"user-7")); }
            return new PaginaReservas(contenido,1000,filtros.page(),100);
        }).when(consultas).consultarReservas(any());
        when(store.preparar(eq(7L),any())).thenReturn(new PreparacionCancelacion(intento(EstadoCancelacion.SOLICITADA,7L),true));
        var evidencia=new CancelacionConfirmada(8251L,"externo",AHORA);
        when(remoto.cancelar(solicitud,8251L)).thenReturn(evidencia);
        when(store.confirmar(7L,evidencia)).thenReturn(intento(EstadoCancelacion.CANCELADA,7L));
        assertEquals(EstadoCancelacion.CANCELADA,service.cancelar(solicitud).estado());
        verify(consultas,times(10)).consultarReservas(any());verify(remoto,times(1)).cancelar(solicitud,8251L);
    }

}
