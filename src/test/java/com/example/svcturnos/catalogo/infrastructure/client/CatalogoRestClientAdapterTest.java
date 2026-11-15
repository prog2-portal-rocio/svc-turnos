package com.example.svcturnos.catalogo.infrastructure.client;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException.Motivo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CatalogoRestClientAdapterTest {

    private static final String BASE_URL = "http://catalogo.prueba";
    private static final String JWT_VALIDADO = "jwt-validado-de-prueba";
    private static final String PROFESIONAL = """
            {"id":15,"categoryId":1,"firstName":"Ana","lastName":"Gomez","enabled":%s,
             "createdAt":"2026-07-01T12:00:00Z","updatedAt":"2026-09-10T13:40:00Z",
             "category":{"id":1,"name":"Clinica medica","description":"Atencion clinica general",
                         "enabled":%s,"createdAt":"2026-07-01T12:00:00Z","updatedAt":null}}
            """;
    private static final String HORARIOS = """
            [{"id":21,"professionalId":15,"dayOfWeek":"MONDAY","startTime":"09:00",
              "endTime":"12:00","slotDurationMinutes":30,"enabled":true,
              "createdAt":"2026-07-01T12:00:00Z","updatedAt":null},
             {"id":22,"professionalId":15,"dayOfWeek":"FRIDAY","startTime":"14:00:00",
              "endTime":"18:00:00","slotDurationMinutes":60,"enabled":false,
              "createdAt":null,"updatedAt":"2026-09-10T13:40:00Z"}]
            """;

    private MockRestServiceServer server;
    private CatalogoRestClientAdapter adapter;

    @BeforeEach
    void configurar() {
        var builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CatalogoRestClientAdapter(builder.build());
        SecurityContextHolder.getContext().setAuthentication(autenticacionJwt());
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void consultaProfesionalConElContratoRealYConservaTodosLosEstados(boolean habilitado, boolean categoriaHabilitada) {
        server.expect(requestTo(BASE_URL + "/api/catalogo/profesionales/15"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_VALIDADO))
                .andRespond(withSuccess(PROFESIONAL.formatted(habilitado, categoriaHabilitada), MediaType.APPLICATION_JSON));

        var profesional = adapter.consultarProfesional(15L);

        assertEquals(15L, profesional.id());
        assertEquals(1L, profesional.categoryId());
        assertEquals("Ana", profesional.firstName());
        assertEquals("Gomez", profesional.lastName());
        assertEquals(habilitado, profesional.enabled());
        assertEquals(Instant.parse("2026-07-01T12:00:00Z"), profesional.createdAt());
        assertEquals(Instant.parse("2026-09-10T13:40:00Z"), profesional.updatedAt());
        assertEquals(1L, profesional.category().id());
        assertEquals("Clinica medica", profesional.category().name());
        assertEquals("Atencion clinica general", profesional.category().description());
        assertEquals(categoriaHabilitada, profesional.category().enabled());
        assertEquals(profesional.createdAt(), profesional.category().createdAt());
        assertNull(profesional.category().updatedAt());
    }

    @Test
    void consultaHorariosSinDescartarDeshabilitadosNiReinterpretarHoras() {
        server.expect(requestTo(BASE_URL + "/api/catalogo/profesionales/15/horarios"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT_VALIDADO))
                .andRespond(withSuccess(HORARIOS, MediaType.APPLICATION_JSON));

        var horarios = adapter.consultarHorarios(15L);

        assertEquals(2, horarios.size());
        var primero = horarios.getFirst();
        assertEquals(21L, primero.id());
        assertEquals(15L, primero.professionalId());
        assertEquals(DayOfWeek.MONDAY, primero.dayOfWeek());
        assertEquals(LocalTime.of(9, 0), primero.startTime());
        assertEquals(LocalTime.of(12, 0), primero.endTime());
        assertEquals(30, primero.slotDurationMinutes());
        assertTrue(primero.enabled());
        assertEquals(Instant.parse("2026-07-01T12:00:00Z"), primero.createdAt());
        assertNull(primero.updatedAt());
        var segundo = horarios.get(1);
        assertEquals(22L, segundo.id());
        assertEquals(15L, segundo.professionalId());
        assertEquals(DayOfWeek.FRIDAY, segundo.dayOfWeek());
        assertEquals(LocalTime.of(14, 0), segundo.startTime());
        assertEquals(LocalTime.of(18, 0), segundo.endTime());
        assertEquals(60, segundo.slotDurationMinutes());
        assertFalse(segundo.enabled());
        assertNull(segundo.createdAt());
        assertEquals(Instant.parse("2026-09-10T13:40:00Z"), segundo.updatedAt());
    }

    @Test
    void permiteAgendaVaciaSoloCuandoLaRespuestaEsUnaListaVacia() {
        server.expect(anything()).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertTrue(adapter.consultarHorarios(15L).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("erroresHttp")
    void distingueErroresHttpSinExponerElBodyNiReintentar(boolean horarios, int status, Motivo motivo) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"detail\":\"" + JWT_VALIDADO + "\"}"));

        var error = assertThrows(ConsultaCatalogoException.class, () -> consultar(horarios));

        assertEquals(motivo, error.getMotivo());
        assertFalse(error.getMessage().contains(JWT_VALIDADO));
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @MethodSource("fallosDeRed")
    void distingueTimeoutDeServicioInaccesible(IOException causa, Motivo motivo) {
        server.expect(anything()).andRespond(withException(causa));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarHorarios(15L));

        assertEquals(motivo, error.getMotivo());
    }

    @ParameterizedTest
    @MethodSource("autenticacionesInvalidas")
    void noRealizaConsultasSinUnJwtAutenticado(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(15L));

        assertEquals(Motivo.NO_AUTENTICADO, error.getMotivo());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void rechazaIdsInvalidosAntesDeRealizarLaConsulta(Long id) {
        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(id));
        assertEquals(Motivo.SOLICITUD_INVALIDA, error.getMotivo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "no es JSON"})
    void respuestaDeProfesionalInvalidaNoSeConfundeCon404(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(15L));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[null]", "[{}]", "no es JSON"})
    void respuestaDeHorariosInvalidaNoSeConvierteEnAgendaVacia(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarHorarios(15L));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    @Test
    void noAceptaHorariosDeOtroProfesional() {
        server.expect(anything()).andRespond(withSuccess(HORARIOS.replace("\"professionalId\":15",
                "\"professionalId\":99"), MediaType.APPLICATION_JSON));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarHorarios(15L));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "categoryId", "firstName", "lastName", "enabled", "category"})
    void rechazaProfesionalesSinCamposObligatorios(String campo) {
        var json = JsonMapper.builder().build();
        var dto = (ObjectNode) json.readTree(PROFESIONAL.formatted(true, true));
        dto.remove(campo);
        server.expect(anything()).andRespond(withSuccess(json.writeValueAsString(dto), MediaType.APPLICATION_JSON));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(15L));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "name", "enabled"})
    void rechazaCategoriasSinCamposObligatorios(String campo) {
        var json = JsonMapper.builder().build();
        var dto = (ObjectNode) json.readTree(PROFESIONAL.formatted(true, true));
        ((ObjectNode) dto.path("category")).remove(campo);
        server.expect(anything()).andRespond(withSuccess(json.writeValueAsString(dto), MediaType.APPLICATION_JSON));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(15L));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "professionalId", "dayOfWeek", "startTime", "endTime", "slotDurationMinutes", "enabled"})
    void rechazaHorariosSinCamposObligatorios(String campo) {
        var json = JsonMapper.builder().build();
        var dtos = json.readTree(HORARIOS);
        ((ObjectNode) dtos.get(0)).remove(campo);
        server.expect(anything()).andRespond(withSuccess(json.writeValueAsString(dtos), MediaType.APPLICATION_JSON));

        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarHorarios(15L));
        assertEquals(Motivo.RESPUESTA_INVALIDA, error.getMotivo());
    }

    private Object consultar(boolean horarios) {
        return horarios ? adapter.consultarHorarios(15L) : adapter.consultarProfesional(15L);
    }

    private static JwtAuthenticationToken autenticacionJwt() {
        var jwt = Jwt.withTokenValue(JWT_VALIDADO).header("alg", "RS256").subject("7")
                .issuer("svc-turnos").audience(List.of("svc-turnos-api", "svc-catalogo-api"))
                .claim("scope", "catalogo:read").expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_catalogo:read")));
    }

    private static Stream<Authentication> autenticacionesInvalidas() {
        var noAutenticado = autenticacionJwt();
        noAutenticado.setAuthenticated(false);
        return Stream.of(null,
                new AnonymousAuthenticationToken("anonimo", "anonimo", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))),
                UsernamePasswordAuthenticationToken.authenticated("usuario", JWT_VALIDADO, List.of()), noAutenticado);
    }

    private static Stream<Arguments> erroresHttp() {
        return Stream.of(false, true).flatMap(horarios -> Stream.of(
                Arguments.of(horarios, 400, Motivo.SOLICITUD_INVALIDA),
                Arguments.of(horarios, 401, Motivo.NO_AUTENTICADO),
                Arguments.of(horarios, 403, Motivo.ACCESO_DENEGADO),
                Arguments.of(horarios, 404, Motivo.PROFESIONAL_NO_ENCONTRADO),
                Arguments.of(horarios, 500, Motivo.ERROR_SERVICIO),
                Arguments.of(horarios, 503, Motivo.ERROR_SERVICIO)));
    }

    private static Stream<Arguments> fallosDeRed() {
        return Stream.of(
                Arguments.of(new SocketTimeoutException("Sin respuesta"), Motivo.TIEMPO_AGOTADO),
                Arguments.of(new HttpTimeoutException("Sin respuesta"), Motivo.TIEMPO_AGOTADO),
                Arguments.of(new HttpConnectTimeoutException("Sin conexion"), Motivo.TIEMPO_AGOTADO),
                Arguments.of(new ConnectException("Conexion rechazada"), Motivo.NO_DISPONIBLE),
                Arguments.of(new UnknownHostException("Host inaccesible"), Motivo.NO_DISPONIBLE));
    }
}
