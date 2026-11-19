package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.domain.model.PacienteReserva;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ConfirmarHoldRestAdapterTest {
    static final String URL = "http://catedra.prueba/api/appointment-holds/hold/confirm";
    static final String OK = """
            {"reservationProcessId":"externo","status":"WAITING_FOR_PHONE","expiresAt":"2026-11-18T15:00:00Z"}
            """;
    final PacienteReserva paciente = new PacienteReserva(7L, "Juan", "Perez");
    MockRestServiceServer server;
    ConfirmarHoldRestAdapter adapter;
    RestClient client;

    @BeforeEach void preparar() {
        var builder = RestClient.builder().baseUrl("http://catedra.prueba");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        adapter = new ConfirmarHoldRestAdapter(client, "tecnico-de-prueba");
    }
    @AfterEach void verificar() { server.verify(); }

    @Test void contrato202ConservaVencimientoRealYEnviaSoloCredencialTecnica() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tecnico-de-prueba"))
                .andExpect(content().json("""
                        {"externalPatientId":"user-7","patientFirstName":"Juan","patientLastName":"Perez"}
                        """))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(OK));
        var hold = adapter.confirmar("hold", paciente);
        assertEquals("externo", hold.reservationProcessId());
        assertEquals(Instant.parse("2026-11-18T15:00:00Z"), hold.expiresAt());
    }

    @ParameterizedTest
    @CsvSource({"400,VALIDATION_ERROR,SOLICITUD_RECHAZADA", "400,EXTERNAL_PATIENT_MISMATCH,SOLICITUD_RECHAZADA",
            "403,HOLD_OWNER_MISMATCH,SOLICITUD_RECHAZADA", "403,STUDENT_INTEGRATION_NOT_FOUND,CREDENCIAL_TECNICA_RECHAZADA",
            "404,HOLD_NOT_FOUND,HOLD_NO_ENCONTRADO", "404,HOLD_EXPIRED,HOLD_VENCIDO",
            "404,PROFESSIONAL_NOT_FOUND,SOLICITUD_RECHAZADA", "409,RESERVATION_PROCESS_ALREADY_CONFIRMED,PROCESO_YA_CONFIRMADO"})
    void distingueStatusYCodeSinReintentar(int status, ConfirmarHoldException.Codigo codigo, ConfirmarHoldException.Motivo motivo) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"" + codigo + "\",\"detail\":\"detalle sensible\"}"));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(motivo, error.getMotivo());
        assertEquals(codigo, error.getCodigo());
        assertFalse(error.getMessage().contains("detalle sensible"));
    }

    @ParameterizedTest @ValueSource(ints = {401, 403})
    void rechazoTecnicoSinBodyNoEsAutenticacionDelUsuario(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.CREDENCIAL_TECNICA_RECHAZADA, error.getMotivo());
    }

    @ParameterizedTest @ValueSource(ints = {200, 201, 302, 500, 503})
    void respuestaNoContractualOErrorServidorEsIncierto(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)).body(OK));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "no json",
            "{\"holdId\":\"h\",\"reservationProcessId\":\"p\",\"status\":\"CONFIRMED\"}"})
    void respuesta202IncompletaNoSeConfundeConRechazo(String body) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(body));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }

    @Test void timeoutDespuesDelPostNoRepiteLaSolicitud() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("sin respuesta")));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "token invalido"})
    void sinCredencialTecnicaNoEnviaPost(String token) {
        var sinCredencial = new ConfirmarHoldRestAdapter(client, token);
        var error = assertThrows(ConfirmarHoldException.class, () -> sinCredencial.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.CONFIGURACION_INCOMPLETA, error.getMotivo());
    }
    @ParameterizedTest @ValueSource(strings = {"reservationProcessId", "expiresAt", "status"})
    void holdAceptadoSinIdentificadoresOVencimientoEsIncierto(String campo) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(OK);
        body.remove(campo);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }
    @ParameterizedTest @ValueSource(ints = {400, 404, 409})
    void errorSinCodigoConocidoNoAutorizaRepetirPost(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CODIGO_DESCONOCIDO\"}"));
        var error = assertThrows(ConfirmarHoldException.class, () -> adapter.confirmar("hold", paciente));
        assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
        assertNull(error.getCodigo());
    }

    @Test void datosDeEntradaInvalidosNoEnviaPost() {
        assertThrows(IllegalArgumentException.class, () -> adapter.confirmar("", paciente));
        assertThrows(IllegalArgumentException.class, () -> adapter.confirmar("hold", null));
    }

}
