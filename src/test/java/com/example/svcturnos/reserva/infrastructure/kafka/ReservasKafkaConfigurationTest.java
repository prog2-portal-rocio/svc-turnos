package com.example.svcturnos.reserva.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReservasKafkaConfigurationTest {
    ApplicationContextRunner runner(boolean autoCommit) {
        return new ApplicationContextRunner().withUserConfiguration(ReservasKafkaConfiguration.class)
                .withBean(ConsumerFactory.class, () -> new DefaultKafkaConsumerFactory<byte[], byte[]>(Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit)))
                .withPropertyValues("turnos.kafka.enabled=true", "turnos.kafka.actions-topic=acciones-prueba",
                        "spring.kafka.consumer.group-id=grupo-prueba", "spring.kafka.bootstrap-servers=localhost:9092");
    }

    @Test void configuracionValidaDetieneContenedorAnteFallosSinDescartarMensajes() {
        runner(false).run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(CommonContainerStoppingErrorHandler.class, context.getBean("reservasKafkaErrorHandler"));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"turnos.kafka.actions-topic", "spring.kafka.consumer.group-id", "spring.kafka.bootstrap-servers"})
    void faltaDeConfiguracionNoHabilitaUnConsumidorEquivocado(String propiedad) {
        runner(false).withPropertyValues(propiedad + "=").run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void autoCommitNoPuedeAnularLaDurabilidad() {
        runner(true).run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest @ValueSource(strings = {"record", "batch", "manual"})
    void exigeElModoDeConfirmacionAcordado(String modo) {
        runner(false).withPropertyValues("spring.kafka.listener.ack-mode=" + modo)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void integracionDeshabilitadaNoCreaConsumidorNiErrorHandler() {
        runner(false).withPropertyValues("turnos.kafka.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("reservasKafkaErrorHandler"));
        });
    }
}
