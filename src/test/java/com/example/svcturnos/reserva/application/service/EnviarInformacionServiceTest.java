package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

class EnviarInformacionServiceTest {
    final UsuarioAutenticadoPort usuario=mock(UsuarioAutenticadoPort.class);
    final ProcesoReservaPersistencePort procesos=mock(ProcesoReservaPersistencePort.class);
    final InboxReservasPort inbox=mock(InboxReservasPort.class);
    final OutboxInformacionPort outbox=mock(OutboxInformacionPort.class);
    final EnviarInformacionService servicio=new EnviarInformacionService(usuario,procesos,inbox,outbox,true,GRUPO,TOPIC);
    void preparar() {
        when(usuario.obtenerUsuarioId()).thenReturn(7L);
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso()));
        when(inbox.buscarSolicitudesPorProceso(42L)).thenReturn(List.of(solicitud()));
    }
    @Test void identidadRealYRequestPersistidoControlanLaIntencion() {
        preparar();
        var esperado=envio(EstadoEnvioInformacion.PENDIENTE);
        when(outbox.registrar(eq(42L),eq(7L),eq(10L),any(),any())).thenReturn(esperado);
        assertEquals(esperado,servicio.enviar(42L,NUMERO));
        verify(outbox).registrar(42L,7L,10L,new Telefono(NUMERO),new DestinoInformacion(GRUPO,TOPIC));
    }
    @Test void procesoAjenoNoBuscaNiGuardaSolicitudes() {
        preparar();when(usuario.obtenerUsuarioId()).thenReturn(8L);
        var error=assertThrows(EnviarInformacionException.class, () -> servicio.enviar(42L,NUMERO));
        assertEquals(EnviarInformacionException.Motivo.PROCESO_AJENO,error.getMotivo());
        verifyNoInteractions(outbox);
        verify(inbox,never()).buscarSolicitudesPorProceso(any());
    }
    @Test void solicitudAusenteEsControlada() {
        preparar();when(inbox.buscarSolicitudesPorProceso(42L)).thenReturn(List.of());
        var error=assertThrows(EnviarInformacionException.class, () -> servicio.enviar(42L,NUMERO));
        assertEquals(EnviarInformacionException.Motivo.SOLICITUD_NO_DISPONIBLE,error.getMotivo());verifyNoInteractions(outbox);
    }
    @Test void falloPostgresNoSeConfundeConAceptacion() {
        preparar();when(outbox.registrar(any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("detalle privado"));
        var error=assertThrows(EnviarInformacionException.class, () -> servicio.enviar(42L,NUMERO));
        assertEquals(EnviarInformacionException.Motivo.PERSISTENCIA_FALLIDA,error.getMotivo());
        assertFalse(error.getMessage().contains("detalle privado"));
    }
    @Test void publicacionDeshabilitadaNoAceptaTelefonosQueNoPodraEnviar() {
        var deshabilitado=new EnviarInformacionService(usuario,procesos,inbox,outbox,false,GRUPO,TOPIC);
        var error=assertThrows(EnviarInformacionException.class, () -> deshabilitado.enviar(42L,NUMERO));
        assertEquals(EnviarInformacionException.Motivo.CONFIGURACION_INCOMPLETA,error.getMotivo());verifyNoInteractions(outbox);
    }
    @Test void procesoInexistenteNoAceptaInformacion() {
        preparar();when(procesos.buscarPorId(42L)).thenReturn(Optional.empty());
        var error=assertThrows(EnviarInformacionException.class, () -> servicio.enviar(42L,NUMERO));
        assertEquals(EnviarInformacionException.Motivo.PROCESO_INEXISTENTE,error.getMotivo());verifyNoInteractions(outbox);
    }
    @Test void identificadorInvalidoNoConsultaPostgres() {
        assertThrows(IllegalArgumentException.class, () -> servicio.enviar(0L,NUMERO));verifyNoInteractions(procesos,inbox,outbox);
    }
    @Test void solicitudesAmbiguasNoSeEligenArbitrariamente() {
        preparar();when(inbox.buscarSolicitudesPorProceso(42L)).thenReturn(List.of(solicitud(),solicitud()));
        var error=assertThrows(EnviarInformacionException.class, () -> servicio.enviar(42L,NUMERO));
        assertEquals(EnviarInformacionException.Motivo.SOLICITUD_NO_DISPONIBLE,error.getMotivo());verifyNoInteractions(outbox);
    }

}
