package com.example.svcturnos.disponibilidad.infrastructure.client;

import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException.Motivo;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException.Codigo;
import com.example.svcturnos.disponibilidad.domain.model.EstadoOcupacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.net.http.HttpTimeoutException;
import java.time.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OcupacionesRestClientAdapterTest {
    private static final LocalDate FECHA = LocalDate.of(2026, 9, 14);
    private static final String URL = "http://catedra.prueba/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-14";
    private static final String RESPUESTA = """
            {"professionalId":15,"from":"2026-09-14","to":"2026-09-14","occupancies":[
              {"date":"2026-09-14","startTime":"09:00","status":"CONFIRMED","expiresAt":null},
              {"date":"2026-09-14","startTime":"09:30:00","status":"HELD","expiresAt":"2026-09-10T14:44:50Z"}]}
            """;
    private MockRestServiceServer server;
    private OcupacionesRestClientAdapter adapter;
    private RestClient client;

    @BeforeEach
    void preparar() {
        var builder = RestClient.builder().baseUrl("http://catedra.prueba");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        adapter = new OcupacionesRestClientAdapter(client, "token-tecnico-de-prueba");
    }

    @AfterEach
    void verificar() { server.verify(); }

    @Test
    void consultaElDiaInclusivoSinPaginacionConAutenticacionTecnica() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-tecnico-de-prueba"))
                .andRespond(withSuccess(RESPUESTA, MediaType.APPLICATION_JSON));
        var posiciones = adapter.consultarOcupaciones(15L, FECHA);
        assertEquals(2, posiciones.size());
        assertEquals(EstadoOcupacion.CONFIRMED, posiciones.getFirst().estado());
        assertEquals(LocalTime.of(9, 30), posiciones.getLast().inicio());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), posiciones.getLast().venceEn());
    }

    @Test
    void listaVaciaEsRespuestaValida() {
        String body = "{\"professionalId\":15,\"from\":\"2026-09-14\",\"to\":\"2026-09-14\",\"occupancies\":[]}";
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertTrue(adapter.consultarOcupaciones(15L, FECHA).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"400,SOLICITUD_INVALIDA", "401,NO_AUTENTICADO", "403,ACCESO_DENEGADO", "404,NO_ENCONTRADO",
            "500,ERROR_SERVICIO", "503,ERROR_SERVICIO", "302,ERROR_SERVICIO"})
    void diferenciaErroresHttpAunqueNoTenganBody(int status, Motivo motivo) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status)));
        var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
        assertEquals(motivo, error.getMotivo());
        assertNull(error.getCodigo());
    }

    @ParameterizedTest
    @CsvSource({"400,INVALID_DATE_RANGE", "403,STUDENT_INTEGRATION_NOT_FOUND"})
    void conservaElCodeFuncionalSinExponerDetail(int status, Codigo codigo) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"code\":\"" + codigo + "\",\"detail\":\"detalle externo\"}"));
        var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
        assertEquals(codigo, error.getCodigo());
        assertFalse(error.getMessage().contains("detalle externo"));
    }

    @ParameterizedTest
    @MethodSource("respuestasInvalidas")
    void respuestaIncompletaOInconsistenteNoDevuelveDisponibilidadTotal(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    static Stream<String> respuestasInvalidas() {
        return Stream.of("", "null", "{}", "no json", RESPUESTA.replace("\"professionalId\":15", "\"professionalId\":99"),
                RESPUESTA.replace("\"from\":\"2026-09-14\"", "\"from\":\"2026-09-13\""),
                RESPUESTA.replace("\"to\":\"2026-09-14\"", "\"to\":\"2026-09-15\""),
                RESPUESTA.replace("\"date\":\"2026-09-14\"", "\"date\":\"2026-09-15\""),
                RESPUESTA.replace("\"CONFIRMED\"", "\"CANCELLED\""),
                RESPUESTA.replace("\"expiresAt\":\"2026-09-10T14:44:50Z\"", "\"expiresAt\":null"),
                RESPUESTA.replace("\"startTime\":\"09:00\"", "\"startTime\":null"),
                RESPUESTA.replace("\"09:00\"", "\"09:00Z\""));
    }

    @ParameterizedTest
    @MethodSource("erroresRed")
    void diferenciaTimeoutDeCaida(java.io.IOException causa, Motivo esperado) {
        server.expect(requestTo(URL)).andRespond(withException(causa));
        var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
        assertEquals(esperado, error.getMotivo());
    }

    static Stream<Arguments> erroresRed() {
        return Stream.of(Arguments.of(new SocketTimeoutException(), Motivo.TIEMPO_AGOTADO),
                Arguments.of(new HttpTimeoutException("timeout"), Motivo.TIEMPO_AGOTADO),
                Arguments.of(new ConnectException(), Motivo.NO_DISPONIBLE),
                Arguments.of(new UnknownHostException(), Motivo.NO_DISPONIBLE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "token con espacios", "token\r\n"})
    void noConsultaSinCredencialTecnicaValida(String token) {
        var sinCredencial = new OcupacionesRestClientAdapter(client, token);
        var error = assertThrows(ConsultaOcupacionesException.class, () -> sinCredencial.consultarOcupaciones(15L, FECHA));
        assertEquals(Motivo.CONFIGURACION_INCOMPLETA, error.getMotivo());
    }

    @Test
    void validaParametrosAntesDeAccederALaRed() {
        assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(0L, FECHA));
        assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, null));
    }
}
