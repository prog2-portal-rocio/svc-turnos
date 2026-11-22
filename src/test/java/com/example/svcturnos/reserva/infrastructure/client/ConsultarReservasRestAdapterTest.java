package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ConsultarReservasRestAdapterTest {
    static final String BASE = "http://catedra.prueba";
    static final String URL = BASE + "/api/appointments?externalPatientId=user-7&status=CONFIRMED&page=0&size=20";
    static final String OK = """
            [{"reservationId":8251,"reservationProcessId":"externo","externalPatientId":"user-7",
              "appointmentDate":"2026-12-01","startTime":"09:30","endTime":"10:00:00","professionalId":15,
              "professionalFirstName":"Ana","professionalLastName":"Gomez","patientFirstName":"Paciente",
              "patientLastName":"Prueba","patientPhone":"dato-no-exponer","status":"CONFIRMED",
              "createdAt":"2026-11-20T10:00:00Z","confirmedAt":"2026-11-20T10:01:00Z",
              "cancelledAt":null,"cancellationReason":null}]
            """;
    final CriteriosConsultaReservas criterios = new CriteriosConsultaReservas(null, null, null, 0, 20, List.of());
    MockRestServiceServer server;
    ConsultarReservasRestAdapter adapter;
    RestClient client;
    @BeforeEach void preparar() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        adapter = new ConsultarReservasRestAdapter(client, "tecnico-prueba", BASE);
    }
    @AfterEach void verificar() { server.verify(); }

    @Test void contratoRealConPaginacionYBearerTecnicoNoConservaDatosDelPaciente() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tecnico-prueba"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON).header("X-Total-Count", "31"));
        var pagina = adapter.consultar("user-7", criterios);
        assertEquals(31, pagina.totalElements());
        assertEquals(LocalTime.of(9, 30), pagina.content().getFirst().startTime());
        assertEquals(8251L, pagina.content().getFirst().reservationId());
        assertFalse(pagina.toString().contains("dato-no-exponer"));
    }

    @ParameterizedTest @EnumSource(EstadoReservaCentral.class)
    void enviaFiltrosInclusivosOrdenMultipleYPaginacion(EstadoReservaCentral status) {
        var fecha = LocalDate.of(2026, 12, 1);
        var filtros = new CriteriosConsultaReservas(status, fecha, fecha, 2, 10,
                List.of("appointmentDate,desc", "startTime,asc"));
        server.expect(requestTo(BASE + "/api/appointments?externalPatientId=user-7&status=" + status
                        + "&page=2&size=10&from=2026-12-01&to=2026-12-01&sort=appointmentDate,desc&sort=startTime,asc"))
                .andRespond(withSuccess(OK.replace("CONFIRMED", status.name()), MediaType.APPLICATION_JSON)
                        .header("X-Total-Count", "31"));
        assertEquals(status, adapter.consultar("user-7", filtros).content().getFirst().status());
    }

    @Test void listaVaciaConTotalEsUnResultadoValido() {
        server.expect(requestTo(URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("X-Total-Count", "0"));
        assertTrue(adapter.consultar("user-7", criterios).content().isEmpty());
    }

    @ParameterizedTest @CsvSource({"400,SOLICITUD_RECHAZADA", "401,CREDENCIAL_TECNICA_RECHAZADA",
            "403,CREDENCIAL_TECNICA_RECHAZADA", "404,RESPUESTA_INVALIDA", "302,RESPUESTA_INVALIDA",
            "500,SERVICIO_NO_DISPONIBLE", "503,SERVICIO_NO_DISPONIBLE"})
    void erroresNoSonAusenciaDeReservasNiExponenElDetalle(int status, ConsultaReservasException.Motivo motivo) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"INVALID_DATE_RANGE\",\"detail\":\"dato privado\"}"));
        var error = assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios));
        assertEquals(motivo, error.getMotivo());
        assertFalse(error.getMessage().contains("dato privado"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "no json", "[null]",
            "[{\"reservationId\":1}]"})
    void rechazaRespuestasIncompletas(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header("X-Total-Count", "1"));
        assertEquals(ConsultaReservasException.Motivo.RESPUESTA_INVALIDA,
                assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios)).getMotivo());
    }

    @ParameterizedTest @ValueSource(strings = {"", "-1", "no-numero"})
    void exigeUnTotalValido(String total) {
        server.expect(requestTo(URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("X-Total-Count", total));
        assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios));
    }

    @ParameterizedTest @ValueSource(strings = {"user-8", ""})
    void rechazaReservasDeOtroPaciente(String paciente) {
        server.expect(requestTo(URL)).andRespond(withSuccess(OK.replace("user-7", paciente), MediaType.APPLICATION_JSON)
                .header("X-Total-Count", "1"));
        assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios));
    }

    @Test void timeoutYConexionFallidaSonDiferenciablesSinReintentos() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("detalle privado")));
        assertEquals(ConsultaReservasException.Motivo.TIMEOUT,
                assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios)).getMotivo());
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("conexion privada")));
        assertEquals(ConsultaReservasException.Motivo.CONEXION_FALLIDA,
                assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios)).getMotivo());
    }

    @Test void configuracionAusenteNoDisparaHTTP() {
        var sinToken = new ConsultarReservasRestAdapter(client, "", BASE);
        var sinUrl = new ConsultarReservasRestAdapter(client, "tecnico-prueba", "");
        assertEquals(ConsultaReservasException.Motivo.CONFIGURACION_INCOMPLETA,
                assertThrows(ConsultaReservasException.class, () -> sinToken.consultar("user-7", criterios)).getMotivo());
        assertThrows(ConsultaReservasException.class, () -> sinUrl.consultar("user-7", criterios));
    }
    @Test void totalAusenteNoSeInventaDesdeElContenido() {
        server.expect(requestTo(URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios));
    }

    @ParameterizedTest @CsvSource({"reservationId,0", "professionalId,0", "startTime,null",
            "endTime,null", "appointmentDate,null", "status,null", "reservationProcessId,null"})
    void datosObligatoriosInvalidosNoSeDevuelvenComoReservas(String campo, String valor) {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var body = json.readTree(OK);
        ((tools.jackson.databind.node.ObjectNode) body.get(0)).set(campo, json.readTree(valor));
        server.expect(requestTo(URL)).andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON)
                .header("X-Total-Count", "1"));
        assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios));
    }

    @ParameterizedTest @ValueSource(strings = {"2026-11-30", "2026-12-02"})
    void validaQueLaRespuestaRespeteElRangoInclusivo(String fecha) {
        var dia = LocalDate.of(2026, 12, 1);
        var filtros = new CriteriosConsultaReservas(null, dia, dia, 0, 20, List.of());
        server.expect(requestTo(URL + "&from=2026-12-01&to=2026-12-01"))
                .andRespond(withSuccess(OK.replace("2026-12-01", fecha), MediaType.APPLICATION_JSON)
                        .header("X-Total-Count", "1"));
        assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", filtros));
    }

    @Test void unEstadoNoSolicitadoNoSePresentaComoConfirmado() {
        server.expect(requestTo(URL)).andRespond(withSuccess(OK.replace("CONFIRMED", "PHONE_PENDING"), MediaType.APPLICATION_JSON)
                .header("X-Total-Count", "1"));
        assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", criterios));
    }

    @Test void totalInferiorALaPosicionDeLaPaginaSeRechaza() {
        var filtros = new CriteriosConsultaReservas(null, null, null, 1, 20, List.of());
        server.expect(requestTo(URL.replace("page=0", "page=1")))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON).header("X-Total-Count", "20"));
        assertEquals(ConsultaReservasException.Motivo.RESPUESTA_INVALIDA,
                assertThrows(ConsultaReservasException.class, () -> adapter.consultar("user-7", filtros)).getMotivo());
    }

    @Test void paginaFueraDeRangoVaciaConservaElTotalReal() {
        var filtros = new CriteriosConsultaReservas(null, null, null, 1, 20, List.of());
        server.expect(requestTo(URL.replace("page=0", "page=1")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("X-Total-Count", "1"));
        var pagina = adapter.consultar("user-7", filtros);
        assertTrue(pagina.content().isEmpty());
        assertEquals(1, pagina.totalElements());
        assertEquals(1, pagina.page());
    }

}
