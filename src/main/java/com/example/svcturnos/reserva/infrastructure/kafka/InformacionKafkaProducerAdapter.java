package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.EnvioInformacion;
import com.example.svcturnos.reserva.domain.ports.out.PublicarInformacionPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
public class InformacionKafkaProducerAdapter implements PublicarInformacionPort {
    private final KafkaTemplate<byte[], byte[]> kafka;
    private final JsonMapper json;
    private final Duration timeout;
    public InformacionKafkaProducerAdapter(KafkaTemplate<byte[], byte[]> kafka, JsonMapper json,
            @Value("${turnos.kafka.phone-send-timeout:20s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Timeout del productor entre 0 y 30 segundos");
        }
        this.kafka = kafka; this.json = json; this.timeout = timeout;
    }
    @Override public void publicar(EnvioInformacion envio) {
        var payload = Map.of("eventId", envio.eventId().toString(), "eventType", "AdditionalInformationSubmitted",
                "groupId", envio.groupId(), "reservationProcessId", envio.reservationProcessId(), "holdId", envio.holdId(),
                "requestEventId", envio.requestEventId(), "information", Map.of("phoneNumber", envio.telefono().numero()),
                "occurredAt", envio.occurredAt().toString(), "schemaVersion", 1);
        try {
            var mensajeKafka = new ProducerRecord<>(envio.topic(), envio.reservationProcessId().getBytes(StandardCharsets.UTF_8),
                    json.writeValueAsBytes(payload));
            // execute gestiona el recurso, sin usar ni modificar el listener compartido de send.
            kafka.execute(producer -> {
                enviarConAcuse(producer, mensajeKafka);
                return null;
            });
        } catch (RuntimeException _) {
            throw sinConfirmacion();
        }
    }

    private void enviarConAcuse(Producer<byte[], byte[]> producer, ProducerRecord<byte[], byte[]> mensajeKafka) {
        try {
            producer.send(mensajeKafka).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw sinConfirmacion();
        } catch (ExecutionException | TimeoutException | RuntimeException _) {
            throw sinConfirmacion();
        }
    }

    private IllegalStateException sinConfirmacion() {
        // No propagar la excepción del productor: puede contener el payload sensible.
        return new IllegalStateException("La publicación no fue confirmada");
    }
}
