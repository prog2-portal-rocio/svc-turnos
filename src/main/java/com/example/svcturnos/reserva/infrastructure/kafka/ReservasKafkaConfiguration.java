package com.example.svcturnos.reserva.infrastructure.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.core.ConsumerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "turnos.kafka.enabled", havingValue = "true")
public class ReservasKafkaConfiguration {
    @Bean
    public CommonErrorHandler reservasKafkaErrorHandler(@Value("${turnos.kafka.actions-topic:}") String topic,
            @Value("${spring.kafka.consumer.group-id:}") String group,
            @Value("${spring.kafka.bootstrap-servers:}") String bootstrap,
            @Value("${spring.kafka.listener.ack-mode:manual_immediate}") String ackMode, ConsumerFactory<?, ?> consumerFactory) {
        Assert.hasText(topic, "Debe configurar el topic de acciones de la integración");
        Assert.hasText(group, "Debe configurar el consumer group de la integración");
        Assert.hasText(bootstrap, "Debe configurar los brokers de la integración");
        Assert.isTrue("manual_immediate".equalsIgnoreCase(ackMode), "La recepción requiere confirmación manual inmediata");
        Assert.isTrue("false".equalsIgnoreCase(String.valueOf(consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG))), "El consumidor no debe confirmar offsets automáticamente");
        return new CommonContainerStoppingErrorHandler();
    }
}
