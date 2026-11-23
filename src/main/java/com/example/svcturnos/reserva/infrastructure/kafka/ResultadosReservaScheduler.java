package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.application.service.RecuperarResultadosReservaService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "turnos.kafka.enabled", havingValue = "true")
public class ResultadosReservaScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ResultadosReservaScheduler.class);
    private final RecuperarResultadosReservaService servicio;
    private long cursor;
    private long hasta;
    public ResultadosReservaScheduler(RecuperarResultadosReservaService servicio) { this.servicio = servicio; }
    @Scheduled(fixedDelayString = "${turnos.kafka.results-poll-interval:5000}",
            initialDelayString = "${turnos.kafka.results-initial-delay:0}")
    public void recuperar() {
        try {
            if (hasta == 0) { hasta = servicio.ultimoIdPendiente(); }
            cursor = servicio.recuperar(cursor, hasta, 100);
            if (cursor == 0) { hasta = 0; }
        }
        catch (RuntimeException _) { LOG.warn("No se pudo recuperar el lote de resultados; los eventos permanecen en la inbox"); }
    }
}
