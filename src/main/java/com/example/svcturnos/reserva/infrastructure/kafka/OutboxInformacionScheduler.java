package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.application.service.PublicarInformacionService;
import com.example.svcturnos.reserva.domain.model.DestinoInformacion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "turnos.kafka.phone-publication-enabled", havingValue = "true")
public class OutboxInformacionScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxInformacionScheduler.class);
    private final PublicarInformacionService servicio;
    public OutboxInformacionScheduler(PublicarInformacionService servicio,
            @Value("${turnos.kafka.integration-group-id:}") String grupo, @Value("${turnos.kafka.phone-topic:}") String topic) {
        new DestinoInformacion(grupo, topic);
        this.servicio = servicio;
    }
    @Scheduled(fixedDelayString = "${turnos.kafka.outbox-poll-interval:5000}",
            initialDelayString = "${turnos.kafka.outbox-initial-delay:0}")
    public void publicar() {
        try {
            servicio.publicarPendientes(10, 5, Duration.ofSeconds(60), Duration.ofSeconds(10));
        } catch (RuntimeException _) {
            LOG.warn("No se pudo completar el ciclo de publicación de información adicional; el progreso permanece en PostgreSQL");
        }
    }
}
