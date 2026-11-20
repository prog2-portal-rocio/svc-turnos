package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.application.service.PublicarInformacionService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

class OutboxInformacionSchedulerTest {
    @Test void cicloAcotadoYErrorNoBorraLaPublicacion() {
        var servicio=mock(PublicarInformacionService.class);
        var scheduler=new OutboxInformacionScheduler(servicio,GRUPO,TOPIC);
        when(servicio.publicarPendientes(10,5,Duration.ofSeconds(60),Duration.ofSeconds(10))).thenThrow(new IllegalStateException());
        assertDoesNotThrow(scheduler::publicar);
        verify(servicio).publicarPendientes(10,5,Duration.ofSeconds(60),Duration.ofSeconds(10));
    }
}
