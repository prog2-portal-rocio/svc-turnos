package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;

class EventoReservaDecoderTest {
    final EventoReservaDecoder decoder = new EventoReservaDecoder();
    final String body = solicitud("event-1", "process-1", "hold-1");

    @Test void contratoV1ConservaSolicitudYCamposDesconocidos() {
        String ampliado = body.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"futuro\":{\"dato\":true}");
        var recibido = decoder.decodificar(mensaje(ampliado, "process-1", 0));
        assertNull(recibido.invalidez());
        assertEquals("event-1", recibido.evento().eventId());
        assertEquals("PHONE_NUMBER", recibido.evento().solicitud().requiredField());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), recibido.evento().solicitud().expiresAt());
        assertTrue(recibido.json().contains("futuro"));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "null", "[]", "1", "no JSON", "{} {}"})
    void mensajesMalformadosSeConservanComoInvalidos(String texto) {
        assertNotNull(decoder.decodificar(mensaje(texto, "process-1", 0)).invalidez());
    }

    @ParameterizedTest @ValueSource(strings = {"2", "0", "-1", "1.0", "\"1\"", "null", "99999999999999"})
    void esquemaDebeSerEnteroUno(String version) {
        var recibido = decoder.decodificar(mensaje(body.replace("\"schemaVersion\":1", "\"schemaVersion\":" + version), "process-1", 0));
        assertEquals(RecepcionEventoReserva.MotivoInvalido.ESQUEMA_NO_SOPORTADO, recibido.invalidez());
    }

    @ParameterizedTest @ValueSource(strings = {"eventId", "eventType", "reservationProcessId", "holdId", "requiredField", "message", "expiresAt", "occurredAt", "schemaVersion"})
    void faltantesNoSePierdenNiSeInterpretanComoSolicitudValida(String campo) {
        var json = JsonMapper.builder().build();
        var nodo = (ObjectNode) json.readTree(body);
        nodo.remove(campo);
        var recibido = decoder.decodificar(mensaje(json.writeValueAsString(nodo), "process-1", 0));
        assertNull(recibido.evento());
        assertNotNull(recibido.invalidez());
    }

    @ParameterizedTest @ValueSource(strings = {"occurredAt", "expiresAt"})
    void fechasInvalidasNoProvocanBuclesDeProcesamiento(String campo) {
        var json = JsonMapper.builder().build();
        var nodo = (ObjectNode) json.readTree(body);
        nodo.put(campo, "fecha-invalida");
        assertEquals(RecepcionEventoReserva.MotivoInvalido.DATOS_INVALIDOS,
                decoder.decodificar(mensaje(json.writeValueAsString(nodo), "process-1", 0)).invalidez());
    }

    @Test void noCoaccionaTiposNiAdmiteCamposRequeridosDesconocidos() {
        assertNotNull(decoder.decodificar(mensaje(body.replace("\"eventId\":\"event-1\"", "\"eventId\":123"), "process-1", 0)).invalidez());
        assertNotNull(decoder.decodificar(mensaje(body.replace("PHONE_NUMBER", "OTRO_CAMPO"), "process-1", 0)).invalidez());
        assertNotNull(decoder.decodificar(mensaje(body.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":2"), "process-1", 0)).invalidez());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "otro-proceso"})
    void claveDebeCoincidirConElProceso(String clave) {
        assertEquals(RecepcionEventoReserva.MotivoInvalido.CLAVE_INCONSISTENTE,
                decoder.decodificar(mensaje(body, clave, 0)).invalidez());
    }

    @Test void conservaBytesQueNoSonUtf8Valido() {
        byte[] bytes = {(byte) 0xff};
        var mensaje = new MensajeReserva("acciones", 0, 0, bytes, bytes);
        assertNotNull(decoder.decodificar(mensaje).invalidez());
        assertArrayEquals(bytes, mensaje.contenido());
        byte[] copia = mensaje.contenido();
        copia[0] = 1;
        assertArrayEquals(bytes, mensaje.contenido());
    }

    @ParameterizedTest @ValueSource(strings = {"AppointmentConfirmed", "AdditionalInformationRejected", "AppointmentProcessExpired", "AppointmentProcessInvalid", "AppointmentCancelled", "EventoFuturo"})
    void otrosTiposSeReconocenSinAplicarSusReglasDeNegocio(String tipo) {
        var recibido = decoder.decodificar(mensaje(body.replace("AdditionalInformationRequested", tipo), "process-1", 0));
        assertNull(recibido.invalidez());
        assertEquals(tipo, recibido.evento().eventType());
        assertNull(recibido.evento().solicitud());
    }
}
