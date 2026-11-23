package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.application.service.RecuperarResultadosReservaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class ResultadosReservaSchedulerTest {
    final RecuperarResultadosReservaService servicio=mock(RecuperarResultadosReservaService.class);
    final ApplicationContextRunner contexto=new ApplicationContextRunner()
            .withUserConfiguration(ResultadosReservaScheduler.class)
            .withBean(RecuperarResultadosReservaService.class, () -> servicio);

    @Test void recuperacionSeActivaAlArrancarSinUnConsumidorNiBrokerKafka() {
        when(servicio.ultimoIdPendiente()).thenReturn(10L);
        contexto.withPropertyValues("turnos.kafka.enabled=true", "turnos.kafka.results-poll-interval=20",
                "turnos.kafka.results-initial-delay=0").run(iniciado -> {
            assertThat(iniciado).hasSingleBean(ResultadosReservaScheduler.class);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(servicio,atLeastOnce()).recuperar(0,10,100));
        });
    }

    @Test void integracionDeshabilitadaConservaLaOpcionExistente() {
        contexto.withPropertyValues("turnos.kafka.enabled=false").run(iniciado -> {
            assertThat(iniciado).doesNotHaveBean(ResultadosReservaScheduler.class);
            verifyNoInteractions(servicio);
        });
    }
}
