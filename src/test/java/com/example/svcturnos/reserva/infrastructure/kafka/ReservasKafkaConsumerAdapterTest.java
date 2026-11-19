package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.RecibirEventoReservaUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.support.Acknowledgment;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;

class ReservasKafkaConsumerAdapterTest {
    final RecibirEventoReservaUseCase recibir = mock(RecibirEventoReservaUseCase.class);
    final Acknowledgment ack = mock(Acknowledgment.class);
    final ReservasKafkaConsumerAdapter consumer = new ReservasKafkaConsumerAdapter(recibir, new EventoReservaDecoder());

    ConsumerRecord<byte[], byte[]> registro(String contenido) {
        return new ConsumerRecord<>("acciones-prueba", 0, 5L, "process".getBytes(StandardCharsets.UTF_8),
                contenido.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest @EnumSource(EstadoEventoReserva.class)
    void ackOcurreDespuesDeConservarInclusoEventosInvalidosYOtrosTipos(EstadoEventoReserva estado) {
        when(recibir.recibir(any())).thenReturn(new EventoInbox(1L, null, estado, null, null));
        consumer.consumir(registro(solicitud("e", "process", "hold")), ack);
        var orden = inOrder(recibir, ack);
        orden.verify(recibir).recibir(any());
        orden.verify(ack).acknowledge();
        orden.verifyNoMoreInteractions();
    }

    @Test void falloDePersistenciaNoConfirmaNiExponeLaCausaSensible() {
        when(recibir.recibir(any())).thenThrow(new IllegalStateException("contenido-sensible"));
        var registro = registro(solicitud("e", "process", "hold"));
        var error = assertThrows(IllegalStateException.class, () -> consumer.consumir(registro, ack));
        verifyNoInteractions(ack);
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("contenido-sensible"));
    }

    @Test void jsonInvalidoSeEntregaALaInboxNoSeDescartaAntesDePersistir() {
        when(recibir.recibir(any())).thenReturn(new EventoInbox(1L, null, EstadoEventoReserva.INVALIDO, null, null));
        consumer.consumir(registro("JSON roto"), ack);
        verify(recibir).recibir(argThat(r -> r.evento() == null && r.invalidez() != null));
        verify(ack).acknowledge();
    }
}
