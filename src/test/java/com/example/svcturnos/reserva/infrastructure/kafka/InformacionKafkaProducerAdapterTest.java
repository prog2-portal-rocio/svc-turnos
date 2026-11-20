package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.EstadoEnvioInformacion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaOperations;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

@ExtendWith(OutputCaptureExtension.class)
class InformacionKafkaProducerAdapterTest {
    final KafkaTemplate<byte[],byte[]> kafka=mock(KafkaTemplate.class);
    final Producer<byte[],byte[]> producer=mock(Producer.class);
    @BeforeEach void ejecutarConProductor() {
        when(kafka.execute(any())).thenAnswer(invocation -> {
            KafkaOperations.ProducerCallback<byte[],byte[],Object> callback=invocation.getArgument(0);
            return callback.doInKafka(producer);
        });
    }
    final JsonMapper json=JsonMapper.builder().build();
    final InformacionKafkaProducerAdapter adapter=new InformacionKafkaProducerAdapter(kafka,json,Duration.ofMillis(20));
    @Test void contratoExactoTopicClaveYRequestOriginalSinJwt() {
        when(producer.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(RecordMetadata.class)));
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);adapter.publicar(envio);
        ArgumentCaptor<ProducerRecord<byte[],byte[]>> record=ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(record.capture());
        assertEquals(TOPIC,record.getValue().topic());
        assertEquals("externo",new String(record.getValue().key(),StandardCharsets.UTF_8));
        var body=json.readTree(record.getValue().value());
        verify(kafka,never()).setProducerListener(any());
        assertEquals(9,body.size());assertEquals("AdditionalInformationSubmitted",body.path("eventType").asString());
        assertEquals(envio.eventId().toString(),body.path("eventId").asString());
        assertEquals(GRUPO,body.path("groupId").asString());assertEquals("externo",body.path("reservationProcessId").asString());
        assertEquals("hold",body.path("holdId").asString());assertEquals("request-original",body.path("requestEventId").asString());
        assertEquals(NUMERO,body.path("information").path("phoneNumber").asString());
        assertEquals(AHORA.toString(),body.path("occurredAt").asString());assertEquals(1,body.path("schemaVersion").asInt());
        assertFalse(body.has("usuarioId"));assertFalse(body.has("token"));
    }
    @Test void timeoutNoSeMarcaEntregadoNiFiltraPayload() {
        when(producer.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
        var error=assertThrows(IllegalStateException.class, () -> adapter.publicar(envio));
        assertFalse(error.getMessage().contains(NUMERO));assertNull(error.getCause());
        verify(producer,times(1)).send(any(ProducerRecord.class));
    }
    @Test void falloBrokerNoExponeDetalleOriginal() {
        when(producer.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException(NUMERO)));
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
        var error=assertThrows(IllegalStateException.class, () -> adapter.publicar(envio));
        assertFalse(error.getMessage().contains(NUMERO));assertNull(error.getCause());
    }
    @Test void interrupcionConservaBandera() {
        when(producer.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
        Thread.currentThread().interrupt();
        try { assertThrows(IllegalStateException.class, () -> adapter.publicar(envio));assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }
    @ParameterizedTest @ValueSource(longs={-1,0,31000})
    void timeoutDebeSerFinito(long millis) {
        var timeout=Duration.ofMillis(millis);
        assertThrows(IllegalArgumentException.class, () -> new InformacionKafkaProducerAdapter(kafka,json,timeout));
    }
    @Test void noModificaListenerCompartidoAunqueFalleLaPublicacion() {
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException(NUMERO)));
        var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
        assertThrows(IllegalStateException.class, () -> adapter.publicar(envio));
        verify(kafka,never()).setProducerListener(any());
        verify(kafka,never()).send(anyString(),any(byte[].class),any(byte[].class));
    }

    @Test void templateRealConservaListenerYNoRegistraPayload(CapturedOutput output) {
        ProducerFactory<byte[],byte[]> factory=mock(ProducerFactory.class);
        when(factory.createProducer()).thenReturn(producer);
        ProducerListener<byte[],byte[]> listener=mock(ProducerListener.class);
        var compartido=new KafkaTemplate<byte[],byte[]>(factory);
        try {
            compartido.setProducerListener(listener);
            var observado=spy(compartido);
            var aislado=new InformacionKafkaProducerAdapter(observado,json,Duration.ofMillis(20));
            when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException(NUMERO)));
            var envio=envio(EstadoEnvioInformacion.PUBLICANDO);
            assertThrows(IllegalStateException.class, () -> aislado.publicar(envio));
            verify(observado,never()).setProducerListener(any());
            verifyNoInteractions(listener);
            assertFalse(output.getAll().contains(NUMERO));
        } finally { compartido.destroy(); }
    }

}
