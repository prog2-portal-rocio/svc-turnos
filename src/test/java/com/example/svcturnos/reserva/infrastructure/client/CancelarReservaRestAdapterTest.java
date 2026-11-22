package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.model.SolicitudCancelacion;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.SocketTimeoutException;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CancelarReservaRestAdapterTest {
    MockRestServiceServer server;
    CancelarReservaRestAdapter adapter;
    RestClient client;
    final SolicitudCancelacion solicitud = new SolicitudCancelacion("proceso", "Cambio de planes");
    @BeforeEach void preparar() {
        var builder = RestClient.builder().baseUrl("http://catedra.prueba");
        server = MockRestServiceServer.bindTo(builder).build(); client = builder.build();
        adapter = new CancelarReservaRestAdapter(client, "tecnico-prueba");
    }
    @AfterEach void verificar() { server.verify(); }
    @Test void usaProcesoYJwtTecnicoYValidaEvidenciaDefinitiva() {
        server.expect(requestTo("http://catedra.prueba/api/appointments/proceso/cancel"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer tecnico-prueba"))
                .andExpect(content().json("{\"reason\":\"Cambio de planes\"}"))
                .andRespond(withSuccess("""
                        {"reservationId":8251,"reservationProcessId":"proceso","status":"CANCELLED","cancelledAt":"2026-11-21T10:00:00Z"}
                        """, MediaType.APPLICATION_JSON));
        assertEquals(8251L, adapter.cancelar(solicitud, 8251L).reservationId());
    }
    @ParameterizedTest @CsvSource({"400,VALIDATION_ERROR,SOLICITUD_INVALIDA", "401,NO_AUTH,CREDENCIAL_TECNICA_RECHAZADA",
            "403,RESERVATION_OWNER_MISMATCH,PROPIEDAD_RECHAZADA", "403,STUDENT_INTEGRATION_NOT_FOUND,CREDENCIAL_TECNICA_RECHAZADA",
            "404,RESERVATION_NOT_FOUND,RESERVA_NO_ENCONTRADA", "409,RESERVATION_NOT_CANCELLABLE,ESTADO_NO_PERMITIDO",
            "500,ERROR,RESULTADO_INCIERTO", "503,ERROR,RESULTADO_INCIERTO", "202,WAITING,RESULTADO_INCIERTO",
            "400,DESCONOCIDO,RESULTADO_INCIERTO", "403,DESCONOCIDO,RESULTADO_INCIERTO"})
    void clasificaPorStatusYCodigoSinUsarMensaje(int status, String code, CancelarReservaException.Motivo motivo) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"" + code + "\",\"message\":\"dato sensible\"}"));
        var error = assertThrows(CancelarReservaException.class, () -> adapter.cancelar(solicitud, 8251L));
        assertEquals(motivo, error.getMotivo()); assertFalse(error.toString().contains("dato sensible"));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"{}", "null", "no-json",
            "{\"reservationId\":99,\"reservationProcessId\":\"otro\",\"status\":\"CANCELLED\"}"})
    void respuestaIncompletaOInconsistenteEsIncierta(String json) {
        server.expect(anything()).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        assertEquals(CancelarReservaException.Motivo.RESULTADO_INCIERTO,
                assertThrows(CancelarReservaException.class, () -> adapter.cancelar(solicitud, 8251L)).getMotivo());
    }
    @Test void timeoutNoRepitePost() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("sensible")));
        assertTrue(assertThrows(CancelarReservaException.class, () -> adapter.cancelar(solicitud, 8251L)).resultadoIncierto());
    }
    @Test void conexionFallidaSeConservaComoInciertaPorPrudencia() {
        server.expect(anything()).andRespond(withException(new IOException("sensible")));
        assertTrue(assertThrows(CancelarReservaException.class, () -> adapter.cancelar(solicitud, 8251L)).resultadoIncierto());
    }
    @Test void sinCredencialNoEnvia() {
        var sinToken = new CancelarReservaRestAdapter(client, "");
        assertEquals(CancelarReservaException.Motivo.CONFIGURACION_INCOMPLETA,
                assertThrows(CancelarReservaException.class, () -> sinToken.cancelar(solicitud, 8251L)).getMotivo());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"", "{}", "{\"status\":403}", "<html>Forbidden</html>"})
    void rechazo403SinCodigoEsTecnicoConocidoYNoResultadoIncierto(String body) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body(body));
        var error=assertThrows(CancelarReservaException.class,()->adapter.cancelar(solicitud,8251L));
        assertEquals(CancelarReservaException.Motivo.CREDENCIAL_TECNICA_RECHAZADA,error.getMotivo());
        assertFalse(error.resultadoIncierto());
    }

}
