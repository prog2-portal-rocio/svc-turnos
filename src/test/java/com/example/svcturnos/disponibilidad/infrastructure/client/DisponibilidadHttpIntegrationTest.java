package com.example.svcturnos.disponibilidad.infrastructure.client;

import com.example.svcturnos.catalogo.infrastructure.client.CatalogoRestClientAdapter;
import com.example.svcturnos.disponibilidad.application.service.ConsultarDisponibilidadService;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DisponibilidadHttpIntegrationTest {
    private static final LocalDate FECHA = LocalDate.of(2026, 9, 14);
    private static final Instant AHORA = Instant.parse("2026-09-10T14:44:50Z");
    private MockRestServiceServer catalogoServer;
    private MockRestServiceServer catedraServer;
    private ConsultarDisponibilidadService servicio;

    @BeforeEach
    void preparar() {
        var catalogo = RestClient.builder().baseUrl("http://catalogo.prueba");
        var catedra = RestClient.builder().baseUrl("http://catedra.prueba");
        catalogoServer = MockRestServiceServer.bindTo(catalogo).build();
        catedraServer = MockRestServiceServer.bindTo(catedra).build();
        servicio = new ConsultarDisponibilidadService(new CatalogoRestClientAdapter(catalogo.build()),
                new OcupacionesRestClientAdapter(catedra.build(), "tecnico-de-prueba"), Clock.fixed(AHORA, ZoneOffset.UTC));
        var jwt = Jwt.withTokenValue("usuario-validado-de-prueba").header("alg", "RS256").subject("7")
                .issuer("svc-turnos").audience(List.of("svc-catalogo-api", "svc-turnos-api"))
                .claim("scope", "catalogo:read").expiresAt(AHORA.plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        catalogoServer.verify();
        catedraServer.verify();
    }

    private void esperarCatalogo() {
        catalogoServer.expect(requestTo("http://catalogo.prueba/api/catalogo/profesionales/15"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer usuario-validado-de-prueba"))
                .andRespond(withSuccess("""
                        {"id":15,"categoryId":1,"firstName":"Ana","lastName":"Gomez","enabled":true,
                         "category":{"id":1,"name":"Clinica","enabled":true}}
                        """, MediaType.APPLICATION_JSON));
        catalogoServer.expect(requestTo("http://catalogo.prueba/api/catalogo/profesionales/15/horarios"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer usuario-validado-de-prueba"))
                .andRespond(withSuccess("""
                        [{"id":21,"professionalId":15,"dayOfWeek":"MONDAY","startTime":"09:00",
                          "endTime":"11:00:00","slotDurationMinutes":30,"enabled":true}]
                        """, MediaType.APPLICATION_JSON));
    }

    @Test
    void combinaLosDosAdaptadoresRealesConContratosYCredencialesDistintos() {
        esperarCatalogo();
        catedraServer.expect(requestTo("http://catedra.prueba/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-14"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tecnico-de-prueba"))
                .andRespond(withSuccess("""
                        {"professionalId":15,"from":"2026-09-14","to":"2026-09-14","occupancies":[
                         {"date":"2026-09-14","startTime":"09:00","status":"CONFIRMED","expiresAt":null},
                         {"date":"2026-09-14","startTime":"09:30:00","status":"HELD","expiresAt":"2026-09-10T14:45:00Z"},
                         {"date":"2026-09-14","startTime":"10:00","status":"HELD","expiresAt":"2026-09-10T14:44:50Z"}]}
                        """, MediaType.APPLICATION_JSON));
        var resultado = servicio.consultar(15L, FECHA);
        assertEquals(2, resultado.size());
        assertEquals(LocalTime.of(10, 0), resultado.getFirst().inicio());
        assertEquals(LocalTime.of(11, 0), resultado.getLast().fin());
    }

    @Test
    void caidaDeCatedraNoDevuelveLaAgendaCompletaComoDisponible() {
        esperarCatalogo();
        catedraServer.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        var error = assertThrows(ConsultaOcupacionesException.class, () -> servicio.consultar(15L, FECHA));
        assertEquals(ConsultaOcupacionesException.Motivo.ERROR_SERVICIO, error.getMotivo());
    }
}
