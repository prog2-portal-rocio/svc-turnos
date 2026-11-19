package com.example.svcturnos.reserva.infrastructure.web;

import com.example.svcturnos.reserva.application.service.ConfirmarHoldService;
import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.CorrelacionarSolicitudInformacionUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.security.UsuarioAutenticadoAdapter;
import com.example.svcturnos.reserva.infrastructure.web.controller.ConfirmacionHoldController;
import com.example.svcturnos.reserva.infrastructure.web.handler.*;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConfirmacionHoldController.class)
@Import({SecurityConfig.class, JwtConfig.class, ConfirmarHoldService.class, UsuarioAutenticadoAdapter.class,
        HoldExceptionHandler.class, ConfirmacionHoldExceptionHandler.class})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
class ConfirmacionHoldControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean UsuarioPersistencePort usuarios;
    @MockitoBean ProcesoReservaPersistencePort procesos;
    @MockitoBean ConfirmarHoldRemotoPort remoto;
    @MockitoBean InboxReservasPort inbox;
    @MockitoBean CorrelacionarSolicitudInformacionUseCase correlacionar;
    @MockitoBean Clock clock;
    static final String URL = "/api/reservas/holds/42/confirm";
    final Instant ahora = Instant.parse("2026-11-19T20:00:00Z");

    Usuario usuario(Long id, boolean activo, String nombre, String apellido) {
        return new Usuario(id, "prueba", "hash-prueba", nombre, apellido, "test@test.invalid", null, "es", activo);
    }
    Usuario usuario() { return usuario(7L, true, "Juan", "Perez"); }
    String token(Long id) { return new JwtTokenAdapter(encoder).generarToken(usuario(id, true, "Juan", "Perez")); }

    @BeforeEach void preparar() {
        when(clock.instant()).thenReturn(ahora);
    }

    void identidadYHold() {
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario()));
        var hold = new ProcesoReserva(42L, 7L, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30), EstadoProcesoReserva.HOLD_REGISTRADO,
                "hold", "externo", ahora.plusSeconds(300), ahora.minusSeconds(60), ahora, 1L);
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(hold));
        when(procesos.guardar(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test void sinJwtOJwtInvalidoNoHayEfectos() throws Exception {
        mvc.perform(post(URL)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).header("Authorization", "Bearer invalido")).andExpect(status().isUnauthorized());
        verifyNoInteractions(usuarios, procesos, remoto, inbox);
    }

    @Test void identidadYNombresProvienenDelUsuarioPersistidoNoDelBody() throws Exception {
        identidadYHold();
        when(remoto.confirmar(any(), any())).thenReturn(new ConfirmacionInicialAceptada("externo", ahora.plusSeconds(200)));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usuarioId\":99,\"externalPatientId\":\"user-99\",\"patientFirstName\":\"Falso\",\"holdId\":\"ajeno\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.procesoId").value(42))
                .andExpect(jsonPath("$.estado").value("ESPERANDO_TELEFONO"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-19T20:03:20Z"))
                .andExpect(jsonPath("$.holdId").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
        verify(remoto).confirmar("hold", new PacienteReserva(7L, "Juan", "Perez"));
    }

    @Test void usuarioAjenoNoPuedeConfirmarHold() throws Exception {
        identidadYHold();
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario(8L, true, "Otro", "Usuario")));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(8L))).andExpect(status().isForbidden());
        verifyNoInteractions(remoto);
        verify(procesos, never()).guardar(any());
    }

    @Test void usuarioInactivoNoPuedeConfirmar() throws Exception {
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario(7L, false, "Juan", "Perez")));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L))).andExpect(status().isForbidden());
        verifyNoInteractions(procesos, remoto);
    }

    @ParameterizedTest @CsvSource({"A,Perez", "Juan,P", "'',Perez", "Juan,''"})
    void nombresInvalidosNoProvocanPost(String nombre, String apellido) throws Exception {
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario(7L, true, nombre, apellido)));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L))).andExpect(status().isBadRequest());
        verifyNoInteractions(procesos, remoto);
    }

    @Test void inexistenteDevuelve404() throws Exception {
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario()));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L))).andExpect(status().isNotFound());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "no-numero"})
    void identificadorInvalidoDevuelve400(String id) throws Exception {
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario()));
        mvc.perform(post("/api/reservas/holds/" + id + "/confirm").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @CsvSource({"SOLICITUD_RECHAZADA,400", "HOLD_NO_ENCONTRADO,404", "HOLD_VENCIDO,410",
            "PROCESO_YA_CONFIRMADO,409", "CREDENCIAL_TECNICA_RECHAZADA,502", "CONFIGURACION_INCOMPLETA,502", "RESULTADO_INCIERTO,503"})
    void erroresRemotosConProblemDetailNoConfirmanReserva(ConfirmarHoldException.Motivo motivo, int status) throws Exception {
        identidadYHold();
        when(remoto.confirmar(any(), any())).thenThrow(new ConfirmarHoldException(motivo));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.procesoId").value(42)).andExpect(jsonPath("$.code").value("CONFIRMACION_" + motivo));
        verify(remoto, times(1)).confirmar(any(), any());
    }
}
