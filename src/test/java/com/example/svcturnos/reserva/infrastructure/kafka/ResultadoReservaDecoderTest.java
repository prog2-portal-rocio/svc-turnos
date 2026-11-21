package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.ResultadosReservaFixtures.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;

class ResultadoReservaDecoderTest {
    final EventoReservaDecoder decoder = new EventoReservaDecoder();
    @ParameterizedTest @ValueSource(strings={CONFIRMACION,RECHAZO,EXPIRACION,INVALIDEZ,CANCELACION})
    void contratoV1ConservaCamposYExtensionDesconocida(String tipo) {
        var body=resultado(tipo,"e","externo","hold","request",AHORA);
        body.put("extensionFutura","dato");
        var recibido=decoder.decodificar(mensaje(body.toString(),"externo",0));
        assertNull(recibido.invalidez());assertEquals(tipo,recibido.evento().eventType());
        assertEquals(GRUPO,recibido.evento().resultado().groupId());
        assertEquals("request",recibido.evento().resultado().requestEventId());
        assertTrue(recibido.json().contains("extensionFutura"));
    }
    static Stream<Arguments> obligatorios() {
        return Stream.of(CONFIRMACION,RECHAZO,EXPIRACION,INVALIDEZ,CANCELACION).flatMap(tipo -> {
            var comunes=Stream.of("groupId","requestEventId");
            var propios=switch (tipo) {
                case CONFIRMACION,CANCELACION -> Stream.of("reservationId","professionalId","date","startTime","status");
                case RECHAZO -> Stream.of("reason","message","expiresAt");
                default -> Stream.of("reason");
            };
            return Stream.concat(comunes,propios).map(campo -> Arguments.of(tipo,campo));
        });
    }
    @ParameterizedTest @MethodSource("obligatorios")
    void resultadoIncompletoNoSeInterpretaComoValido(String tipo,String campo) {
        var body=resultado(tipo,"e","externo","hold","request",AHORA);body.remove(campo);
        var recibido=decoder.decodificar(mensaje(body.toString(),"externo",0));
        assertEquals(RecepcionEventoReserva.MotivoInvalido.DATOS_INVALIDOS,recibido.invalidez());assertNull(recibido.evento());
    }
    @ParameterizedTest @CsvSource({"reservationId,0", "reservationId,-1", "reservationId,99999999999999999999999999", "professionalId,0"})
    void identificadoresPositivosSinCoercion(String campo,String dato) {
        var body=resultado(CONFIRMACION,"e","externo","hold","request",AHORA);
        body.put(campo,dato);
        assertNotNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
    }
    @ParameterizedTest @ValueSource(strings={"CONFIRMED", "FAILED", "WAITING_FOR_PHONE"})
    void cancelacionExigeSuEstadoContractual(String status) {
        var body=resultado(CANCELACION,"e","externo","hold","request",AHORA);body.put("status",status);
        assertNotNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
    }
    @ParameterizedTest @ValueSource(strings={"09:30","09:30:00"})
    void formatosHorariosOficiales(String hora) {
        var body=resultado(CONFIRMACION,"e","externo","hold","request",AHORA);body.put("startTime",hora);
        assertNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
    }
    @Test void motivoDeCancelacionEsOpcionalYNulo() {
        var body=resultado(CANCELACION,"e","externo","hold","request",AHORA);body.putNull("reason");
        var recibido=decoder.decodificar(mensaje(body.toString(),"externo",0));
        assertNull(recibido.invalidez());assertNull(recibido.evento().resultado().reason());
        assertEquals(8251L,recibido.evento().resultado().reservationId());
    }
    @ParameterizedTest @ValueSource(longs={0,-1})
    void identificadorNumericoNoPositivoEsInvalido(long identificador) {
        var body=resultado(CONFIRMACION,"e","externo","hold","request",AHORA);body.put("reservationId",identificador);
        assertNotNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
    }
    @ParameterizedTest @CsvSource({"date,2026-02-30", "startTime,25:00", "startTime,09:30:00.0", "status,WAITING_FOR_PHONE"})
    void formatosYEstadoDeConfirmacionNoSeInventan(String campo,String valor) {
        var body=resultado(CONFIRMACION,"e","externo","hold","request",AHORA);body.put(campo,valor);
        assertNotNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
    }

    @ParameterizedTest @ValueSource(strings={CONFIRMACION,RECHAZO,EXPIRACION,INVALIDEZ,CANCELACION})
    void referenciaContractualDebeSerUnTextoNoVacio(String tipo) {
        var body=resultado(tipo,"e","externo","hold","request",AHORA);
        body.putNull("requestEventId");
        assertNotNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
        body.put("requestEventId"," ");
        assertNotNull(decoder.decodificar(mensaje(body.toString(),"externo",0)).invalidez());
    }

}
