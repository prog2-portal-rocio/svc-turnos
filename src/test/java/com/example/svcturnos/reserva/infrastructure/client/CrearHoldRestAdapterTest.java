package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;
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

class CrearHoldRestAdapterTest {
    static final String URL = "http://catedra.prueba/api/appointment-holds";
    static final String OK = """
            {"holdId":"hold","reservationProcessId":"externo","status":"HELD","expiresAt":"2026-11-18T15:00:00Z"}
            """;
    final SolicitudHold solicitud = new SolicitudHold(15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30));
    MockRestServiceServer server;
    CrearHoldRestAdapter adapter;
    RestClient client;

    @BeforeEach void preparar() {
        var builder = RestClient.builder().baseUrl("http://catedra.prueba");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        adapter = new CrearHoldRestAdapter(client, "tecnico-de-prueba");
    }
    @AfterEach void verificar() { server.verify(); }

    @Test void contrato201ConservaVencimientoRealYEnviaSoloCredencialTecnica() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tecnico-de-prueba"))
                .andExpect(content().json("""
                        {"professionalId":15,"date":"2026-12-01","startTime":"09:30:00","externalPatientId":"user-7"}
                        """))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(OK));
        var hold = adapter.crear(solicitud, "user-7");
        assertEquals("hold", hold.holdId());
        assertEquals("externo", hold.procesoExternoId());
        assertEquals(Instant.parse("2026-11-18T15:00:00Z"), hold.expiresAt());
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_SLOT,SOLICITUD_INVALIDA", "400,PROFESSIONAL_DISABLED,SOLICITUD_INVALIDA",
            "400,VALIDATION_ERROR,SOLICITUD_INVALIDA", "403,STUDENT_INTEGRATION_NOT_FOUND,CREDENCIAL_TECNICA_RECHAZADA",
            "404,PROFESSIONAL_NOT_FOUND,PROFESIONAL_INEXISTENTE", "409,SLOT_ALREADY_HELD,CONFLICTO",
            "409,SLOT_ALREADY_RESERVED,CONFLICTO"})
    void distingueStatusYCodeSinReintentar(int status, CrearHoldException.Codigo codigo, CrearHoldException.Motivo motivo) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"" + codigo + "\",\"detail\":\"detalle sensible\"}"));
        var error = assertThrows(CrearHoldException.class, () -> adapter.crear(solicitud, "user-7"));
        assertEquals(motivo, error.getMotivo());
        assertEquals(codigo, error.getCodigo());
        assertFalse(error.getMessage().contains("detalle sensible"));
    }

    @ParameterizedTest @ValueSource(ints = {401, 403})
    void rechazoTecnicoSinBodyNoEsAutenticacionDelUsuario(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));
        var error = assertThrows(CrearHoldException.class, () -> adapter.crear(solicitud, "user-7"));
        assertEquals(CrearHoldException.Motivo.CREDENCIAL_TECNICA_RECHAZADA, error.getMotivo());
    }

    @ParameterizedTest @ValueSource(ints = {200, 202, 302, 500, 503})
    void respuestaNoContractualOErrorServidorEsIncierto(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)).body(OK));
        var error = assertThrows(CrearHoldException.class, () -> adapter.crear(solicitud, "user-7"));
        assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "no json",
            "{\"holdId\":\"h\",\"reservationProcessId\":\"p\",\"status\":\"CONFIRMED\"}"})
    void respuesta201IncompletaNoSeConfundeConRechazo(String body) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body));
        var error = assertThrows(CrearHoldException.class, () -> adapter.crear(solicitud, "user-7"));
        assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }

    @Test void timeoutDespuesDelPostNoRepiteLaSolicitud() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("sin respuesta")));
        var error = assertThrows(CrearHoldException.class, () -> adapter.crear(solicitud, "user-7"));
        assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " ", "token invalido"})
    void sinCredencialTecnicaNoEnviaPost(String token) {
        var sinCredencial = new CrearHoldRestAdapter(client, token);
        var error = assertThrows(CrearHoldException.class, () -> sinCredencial.crear(solicitud, "user-7"));
        assertEquals(CrearHoldException.Motivo.CONFIGURACION_INCOMPLETA, error.getMotivo());
    }
    @ParameterizedTest @ValueSource(strings = {"holdId", "reservationProcessId", "expiresAt"})
    void holdAceptadoSinIdentificadoresOVencimientoEsIncierto(String campo) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var body = (tools.jackson.databind.node.ObjectNode) json.readTree(OK);
        body.remove(campo);
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)));
        var error = assertThrows(CrearHoldException.class, () -> adapter.crear(solicitud, "user-7"));
        assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
    }
}
