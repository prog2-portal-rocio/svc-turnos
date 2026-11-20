package com.example.svcturnos.reserva.application.service;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

class PublicarInformacionServiceTest {
    final OutboxInformacionPort outbox=mock(OutboxInformacionPort.class);
    final PublicarInformacionPort kafka=mock(PublicarInformacionPort.class);
    final PublicarInformacionService servicio=new PublicarInformacionService(outbox,kafka,Clock.fixed(AHORA,ZoneOffset.UTC));
    final Duration concesion=Duration.ofSeconds(60), demora=Duration.ofSeconds(10);
    @Test void soloAckPermiteMarcarEnviadaYNoAbreTransaccionHttp() {
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
        when(outbox.reclamar(AHORA,concesion,5)).thenReturn(Optional.of(envio),Optional.empty());
        assertEquals(1,servicio.publicarPendientes(10,5,concesion,demora));
        var orden=inOrder(outbox,kafka);
        orden.verify(outbox).depurarVencidos(AHORA);orden.verify(outbox).reclamar(AHORA,concesion,5);
        orden.verify(kafka).publicar(envio);
        orden.verify(outbox).registrarResultado(envio.eventId(),envio.intentoId(),true,AHORA,demora);
    }
    @Test void timeoutConservaIncertidumbreConMismoEvento() {
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
        when(outbox.reclamar(AHORA,concesion,5)).thenReturn(Optional.of(envio),Optional.empty());
        doThrow(new IllegalStateException()).when(kafka).publicar(envio);
        assertEquals(1,servicio.publicarPendientes(10,5,concesion,demora));
        verify(outbox).registrarResultado(envio.eventId(),envio.intentoId(),false,AHORA,demora);
        verify(kafka,times(1)).publicar(envio);
    }
    @Test void falloDePersistenciaPrevioNoPublica() {
        when(outbox.reclamar(AHORA,concesion,5)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class, () -> servicio.publicarPendientes(10,5,concesion,demora));verifyNoInteractions(kafka);
    }
    @Test void plazoTerminadoEntreReclamoYEnvioNoPublica() {
        var original=envio(EstadoEnvioInformacion.PUBLICANDO);
        var vencido=new EnvioInformacion(original.eventId(),original.procesoId(),original.solicitudId(),original.groupId(),
                original.topic(),original.reservationProcessId(),original.holdId(),original.requestEventId(),original.telefono(),
                original.occurredAt(),AHORA,original.estado(),original.intentoId());
        when(outbox.reclamar(AHORA,concesion,5)).thenReturn(Optional.of(vencido));
        assertEquals(1,servicio.publicarPendientes(1,5,concesion,demora));verifyNoInteractions(kafka);
        verify(outbox).registrarResultado(vencido.eventId(),vencido.intentoId(),false,AHORA,demora);
    }

}
