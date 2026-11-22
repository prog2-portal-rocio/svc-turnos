package com.example.svcturnos.reserva.infrastructure.web;

import com.example.svcturnos.reserva.application.service.CancelarReservaService;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ConsultarReservasUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.security.UsuarioAutenticadoAdapter;
import com.example.svcturnos.reserva.infrastructure.web.controller.CancelacionReservaController;
import com.example.svcturnos.reserva.infrastructure.web.handler.CancelacionReservaExceptionHandler;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CancelacionReservaController.class)
@Import({SecurityConfig.class,JwtConfig.class,CancelarReservaService.class,UsuarioAutenticadoAdapter.class,CancelacionReservaExceptionHandler.class})
@ContextConfiguration(initializers=JwtTestContextInitializer.class)
class CancelacionReservaControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean UsuarioPersistencePort usuarios;
    @MockitoBean ConsultarReservasUseCase consultas;
    @MockitoBean CancelacionReservaPersistencePort store;
    @MockitoBean CancelarReservaRemotaPort remoto;
    static final Instant AHORA=Instant.parse("2026-11-21T10:00:00Z");
    Usuario usuario(boolean activo) {
        return new Usuario(7L,"usuario7","hash-prueba","Nombre","Apellido","u@test.invalid",null,"es",activo);
    }
    String token() { return new JwtTokenAdapter(encoder).generarToken(usuario(true)); }
    @BeforeEach void preparar() {
        when(usuarios.buscarPorLogin("usuario7")).thenReturn(Optional.of(usuario(true)));
        when(store.buscar("externo")).thenReturn(Optional.empty());
        when(consultas.consultarReservas(any())).thenAnswer(call->{
            CriteriosConsultaReservas filtros=call.getArgument(0);return new PaginaReservas(List.of(),0,filtros.page(),filtros.size());
        });
    }
    @Test void sinTokenOInvalido401() throws Exception {
        mvc.perform(post("/api/reservas/externo/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer invalido")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store,consultas,remoto);
    }
    @Test void usuarioInactivoNoPuedeCancelar() throws Exception {
        when(usuarios.buscarPorLogin("usuario7")).thenReturn(Optional.of(usuario(false)));
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer "+token())).andExpect(status().isForbidden());
        verifyNoInteractions(store,consultas,remoto);
    }
    @Test void noAceptaPropietarioArbitrarioYAjenasSon404() throws Exception {
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer "+token())
                .contentType("application/json").content("{\"usuarioId\":8,\"externalPatientId\":\"user-8\"}"))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(remoto);
    }
    @Test void reasonDemasiadoLargo400() throws Exception {
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer "+token())
                .contentType("application/json").content("{\"reason\":\""+"a".repeat(501)+"\"}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(remoto);
    }
    @Test void canceladaEs200YNoExponeUsuarioNiMotivo() throws Exception {
        when(store.buscar("externo")).thenReturn(Optional.of(new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.CANCELADA,AHORA,AHORA,AHORA,false)));
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer "+token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADA"))
                .andExpect(jsonPath("$.reservationId").value(8251)).andExpect(jsonPath("$.usuarioId").doesNotExist());
        verifyNoInteractions(remoto,consultas);
    }
    @Test void intentoInciertoEs202SinPresentarloComoCancelada() throws Exception {
        var central=new ReservaCentral(8251L,"externo","user-7",LocalDate.of(2026,12,1),LocalTime.of(9,0),LocalTime.of(9,30),
                15L,"Ana","Gomez",EstadoReservaCentral.CONFIRMED,AHORA,AHORA,null);
        doReturn(new PaginaReservas(List.of(central),1,0,100)).when(consultas).consultarReservas(any());
        when(store.preparar(7L,central)).thenReturn(new PreparacionCancelacion(new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.INCIERTA,AHORA,AHORA,null,false),false));
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer "+token()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.estado").value("INCIERTA"))
                .andExpect(jsonPath("$.resultadoEn").isEmpty()); verifyNoInteractions(remoto);
    }
    @Test void propiaConRespuestaCentralDefinitivaEs200() throws Exception {
        var central=new ReservaCentral(8251L,"externo","user-7",LocalDate.of(2026,12,1),LocalTime.of(9,0),LocalTime.of(9,30),
                15L,"Ana","Gomez",EstadoReservaCentral.CONFIRMED,AHORA,AHORA,null);
        doReturn(new PaginaReservas(List.of(central),1,0,100)).when(consultas).consultarReservas(any());
        when(store.preparar(7L,central)).thenReturn(new PreparacionCancelacion(new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.SOLICITADA,AHORA,AHORA,null,false),true));
        var evidencia=new CancelacionConfirmada(8251L,"externo",AHORA);
        when(remoto.cancelar(new SolicitudCancelacion("externo",null),8251L)).thenReturn(evidencia);
        when(store.confirmar(7L,evidencia)).thenReturn(new CancelacionReserva("externo",7L,8251L,
                EstadoCancelacion.CANCELADA,AHORA,AHORA,AHORA,false));
        mvc.perform(post("/api/reservas/externo/cancel").header("Authorization","Bearer "+token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADA"));
        verify(store).confirmar(7L,evidencia);
    }

}
