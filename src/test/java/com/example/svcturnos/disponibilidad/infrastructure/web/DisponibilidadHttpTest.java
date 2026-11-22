package com.example.svcturnos.disponibilidad.infrastructure.web;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.model.*;
import com.example.svcturnos.catalogo.domain.ports.out.ConsultarCatalogoPort;
import com.example.svcturnos.disponibilidad.application.service.ConsultarDisponibilidadService;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.model.*;
import com.example.svcturnos.disponibilidad.domain.ports.out.ConsultarOcupacionesPort;
import com.example.svcturnos.disponibilidad.infrastructure.web.controller.DisponibilidadController;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.List;
import java.util.stream.Stream;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DisponibilidadController.class)
@Import({SecurityConfig.class, JwtConfig.class, ConsultarDisponibilidadService.class,
        DisponibilidadHttpTest.TiempoDePrueba.class})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
class DisponibilidadHttpTest {
    static final String RUTA = "/api/disponibilidad";
    static final LocalDate FECHA = LocalDate.of(2026,12,1);
    static final Instant AHORA = Instant.parse("2026-11-21T10:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean ConsultarCatalogoPort catalogo;
    @MockitoBean ConsultarOcupacionesPort ocupaciones;

    @TestConfiguration(proxyBeanMethods = false)
    static class TiempoDePrueba {
        @Bean Clock clock() { return Clock.fixed(AHORA, ZoneOffset.UTC); }
    }

    String token() {
        var usuario = new Usuario(7L,"prueba","hash-prueba","Nombre","Apellido","test@test.invalid",null,"es",true);
        return new JwtTokenAdapter(encoder).generarToken(usuario);
    }

    @BeforeEach void prepararAgenda() {
        var categoria = new Categoria(1L,"Clinica","Categoria de prueba",true,AHORA,AHORA);
        when(catalogo.consultarProfesional(15L)).thenReturn(new Profesional(15L,1L,"Ana","Gomez",true,AHORA,AHORA,categoria));
        when(catalogo.consultarHorarios(15L)).thenReturn(List.of(new HorarioSemanal(21L,15L,FECHA.getDayOfWeek(),
                LocalTime.of(9,0),LocalTime.of(11,0),30,true,AHORA,AHORA)));
        when(ocupaciones.consultarOcupaciones(15L,FECHA)).thenReturn(List.of());
    }

    @Test void respondeJsonDeBloquesLibresCalculadosPorElCasoDeUsoReal() throws Exception {
        when(ocupaciones.consultarOcupaciones(15L,FECHA)).thenReturn(List.of(
                new Ocupacion(FECHA,LocalTime.of(9,0),EstadoOcupacion.CONFIRMED,null),
                new Ocupacion(FECHA,LocalTime.of(9,30),EstadoOcupacion.HELD,AHORA.plusSeconds(60))));
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("""
                        [{"fecha":"2026-12-01","inicio":"10:00:00","fin":"10:30:00"},
                         {"fecha":"2026-12-01","inicio":"10:30:00","fin":"11:00:00"}]
                        """));
        verify(catalogo).consultarProfesional(15L);
        verify(catalogo).consultarHorarios(15L);
        verify(ocupaciones).consultarOcupaciones(15L,FECHA);
    }

    @Test void sinAgendaDevuelve200ConListaVaciaSinConsultarOcupaciones() throws Exception {
        when(catalogo.consultarHorarios(15L)).thenReturn(List.of());
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(ocupaciones);
    }

    @Test void agendaTotalmenteOcupadaDevuelve200ConListaVacia() throws Exception {
        when(ocupaciones.consultarOcupaciones(15L,FECHA)).thenReturn(Stream.of(LocalTime.of(9,0),LocalTime.of(9,30),LocalTime.of(10,0),LocalTime.of(10,30))
                .map(hora -> new Ocupacion(FECHA,hora,EstadoOcupacion.CONFIRMED,null)).toList());
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    static Stream<Arguments> parametrosInvalidos() {
        return Stream.of(Arguments.of(null,"2026-12-01"),Arguments.of("","2026-12-01"),
                Arguments.of("0","2026-12-01"),Arguments.of("-1","2026-12-01"),Arguments.of("texto","2026-12-01"),
                Arguments.of("1.5","2026-12-01"),Arguments.of("9223372036854775808","2026-12-01"),
                Arguments.of("15",null),Arguments.of("15",""),Arguments.of("15","texto"),
                Arguments.of("15","2026-02-30"),Arguments.of("15","2026-13-01"),Arguments.of("15","01/12/2026"));
    }

    @ParameterizedTest @MethodSource("parametrosInvalidos")
    void parametrosInvalidosSon400SinConsultarDependencias(String profesional, String fecha) throws Exception {
        var request = get(RUTA).header("Authorization","Bearer " + token());
        if (profesional != null) { request.param("profesionalId",profesional); }
        if (fecha != null) { request.param("fecha",fecha); }
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PARAMETROS_INVALIDOS"));
        verifyNoInteractions(catalogo,ocupaciones);
    }

    @Test void jwtAusenteOMalformadoEs401() throws Exception {
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer invalido")).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalogo,ocupaciones);
    }

    @Test void jwtValidoSinPermisoDeLecturaEs403AntesDeConsultarCatalogo() throws Exception {
        Instant ahora = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("svc-turnos").subject("7").audience(List.of("svc-turnos-api","svc-catalogo-api"))
                .issuedAt(ahora).expiresAt(ahora.plusSeconds(600)).claim("login","prueba").build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String sinPermiso = encoder.encode(JwtEncoderParameters.from(header,claims)).getTokenValue();
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + sinPermiso)).andExpect(status().isForbidden());
        verifyNoInteractions(catalogo,ocupaciones);
    }

    @ParameterizedTest @CsvSource({"SOLICITUD_INVALIDA,400", "PROFESIONAL_NO_ENCONTRADO,404", "NO_AUTENTICADO,401",
            "ACCESO_DENEGADO,403", "TIEMPO_AGOTADO,504", "ERROR_SERVICIO,503", "NO_DISPONIBLE,503", "RESPUESTA_INVALIDA,502"})
    void erroresDeCatalogoNoSeTransformanEnResultadosVacios(ConsultaCatalogoException.Motivo motivo, int status) throws Exception {
        when(catalogo.consultarProfesional(15L)).thenThrow(new ConsultaCatalogoException(motivo,new IllegalStateException("detalle-privado")));
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("CATALOGO_" + motivo.name()))
                .andExpect(content().string(not(containsString("detalle-privado"))));
        verifyNoInteractions(ocupaciones);
    }

    @ParameterizedTest @CsvSource({"SOLICITUD_INVALIDA,502", "NO_AUTENTICADO,502", "ACCESO_DENEGADO,502", "NO_ENCONTRADO,502",
            "ERROR_SERVICIO,503", "TIEMPO_AGOTADO,504", "NO_DISPONIBLE,503", "RESPUESTA_INVALIDA,502", "CONFIGURACION_INCOMPLETA,502"})
    void erroresDeOcupacionesSonExplicitosYSuJwtTecnicoNoSeConfundeConElDelUsuario(
            ConsultaOcupacionesException.Motivo motivo, int status) throws Exception {
        when(ocupaciones.consultarOcupaciones(15L,FECHA)).thenThrow(new ConsultaOcupacionesException(motivo));
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("OCUPACIONES_" + motivo.name()))
                .andExpect(jsonPath("$.status").value(status));
    }
    @Test void causaDeConversionExternaNoSeConfundeConUnParametroInvalidoDelCliente() throws Exception {
        when(catalogo.consultarProfesional(15L)).thenThrow(new ConsultaCatalogoException(
                ConsultaCatalogoException.Motivo.RESPUESTA_INVALIDA,new IllegalArgumentException("detalle-privado")));
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CATALOGO_RESPUESTA_INVALIDA"))
                .andExpect(content().string(not(containsString("detalle-privado"))));
    }

    @Test void conservaLosSegundosDeLosHorariosDeAtencionEnElJson() throws Exception {
        when(catalogo.consultarHorarios(15L)).thenReturn(List.of(new HorarioSemanal(21L,15L,FECHA.getDayOfWeek(),
                LocalTime.of(9,0,15),LocalTime.of(10,0,15),30,true,AHORA,AHORA)));
        mvc.perform(get(RUTA).param("profesionalId","15").param("fecha",FECHA.toString())
                .header("Authorization","Bearer " + token()))
                .andExpect(status().isOk()).andExpect(content().json("""
                        [{"fecha":"2026-12-01","inicio":"09:00:15","fin":"09:30:15"},
                         {"fecha":"2026-12-01","inicio":"09:30:15","fin":"10:00:15"}]
                        """));
    }

}
