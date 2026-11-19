package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.MensajeReserva;
import com.example.svcturnos.reserva.domain.model.EventoInbox;
import com.example.svcturnos.reserva.domain.model.EstadoEventoReserva;
import com.example.svcturnos.reserva.domain.ports.in.RecibirEventoReservaUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(name = "turnos.kafka.enabled", havingValue = "true")
public class ReservasKafkaConsumerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ReservasKafkaConsumerAdapter.class);
    private final RecibirEventoReservaUseCase recibir;
    private final EventoReservaDecoder decoder;

    public ReservasKafkaConsumerAdapter(RecibirEventoReservaUseCase recibir, EventoReservaDecoder decoder) {
        this.recibir = recibir;
        this.decoder = decoder;
    }

    @KafkaListener(id = "reserva-acciones", topics = "${turnos.kafka.actions-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumir(ConsumerRecord<byte[], byte[]> registro, Acknowledgment ack) {
        EventoInbox resultado;
        try {
            var mensaje = new MensajeReserva(registro.topic(), registro.partition(), registro.offset(), registro.key(), registro.value());
            resultado = recibir.recibir(decoder.decodificar(mensaje));
        } catch (RuntimeException _) {
            // El error handler detiene el contenedor: no descarta ni registra contenido sensible de la excepción/registro.
            throw new IllegalStateException("No se pudo conservar o correlacionar el evento de reservas");
        }
        if (resultado.estado() == EstadoEventoReserva.INVALIDO || resultado.estado() == EstadoEventoReserva.EVENT_ID_EN_CONFLICTO
                || resultado.estado() == EstadoEventoReserva.CORRELACION_INCONSISTENTE) {
            log.warn("Evento de reservas conservado para revisión: estado={}, particion={}, offset={}",
                    resultado.estado(), registro.partition(), registro.offset());
        }
        ack.acknowledge();
    }
}
