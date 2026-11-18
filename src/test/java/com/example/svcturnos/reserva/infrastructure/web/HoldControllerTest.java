package com.example.svcturnos.reserva.infrastructure.web;

import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.reserva.application.service.CrearHoldService;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.security.UsuarioAutenticadoAdapter;
import com.example.svcturnos.reserva.infrastructure.web.controller.HoldController;
import com.example.svcturnos.reserva.infrastructure.web.handler.HoldExceptionHandler;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HoldController.class)
@Import({SecurityConfig.class, JwtConfig.class, CrearHoldService.class, UsuarioAutenticadoAdapter.class, HoldExceptionHandler.class})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
class HoldControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean UsuarioPersistencePort usuarios;
    @MockitoBean ConsultarDisponibilidadUseCase disponibilidad;
    @MockitoBean CrearHoldRemotoPort remoto;
    @MockitoBean ProcesoReservaPersistencePort procesos;
    static final String URL = "/api/reservas/holds";
    static final String BODY = "{\"professionalId\":15,\"date\":\"2026-12-01\",\"startTime\":\"09:30\"}";

    Usuario usuario(Long id, boolean activo) {
        return new Usuario(id, "prueba", "hash-prueba", "Nombre", "Apellido", "test@test.invalid", null, "es", activo);
    }

    String token(Long id) { return new JwtTokenAdapter(encoder).generarToken(usuario(id, true)); }

    void identidad() { when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario(7L, true))); }

    void agendaYGuarda() {
        when(disponibilidad.consultar(15L, LocalDate.of(2026, 12, 1))).thenReturn(List.of(
                new BloqueHorario(LocalDate.of(2026, 12, 1), LocalTime.of(9, 30), LocalTime.of(10, 0))));
        when(procesos.guardar(any())).thenAnswer(invocation -> {
            ProcesoReserva p = invocation.getArgument(0);
            return new ProcesoReserva(42L, p.usuarioId(), p.profesionalId(), p.fecha(), p.hora(), p.estado(),
                    p.holdId(), p.procesoExternoId(), p.vencimientoHold(), p.creadoEn(), p.actualizadoEn(),
                    p.version() == null ? 0L : p.version() + 1);
        });
    }

    @Test void sinTokenOTokenInvalidoEs401SinEfectos() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).header("Authorization", "Bearer invalido").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(usuarios, disponibilidad, procesos, remoto);
    }

    @Test void identidadFirmadaControlaPropietarioEIgnoraIdsElegidosPorElCliente() throws Exception {
        identidad(); agendaYGuarda();
        when(remoto.crear(any(), eq("user-7"))).thenReturn(new HoldCreado("hold", "externo", Instant.parse("2026-11-18T15:00:00Z")));
        String body = BODY.replace("}", ",\"usuarioId\":999,\"externalPatientId\":\"user-999\"}");
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.procesoId").value(42))
                .andExpect(jsonPath("$.estado").value("HOLD_REGISTRADO"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-18T15:00:00Z"))
                .andExpect(jsonPath("$.holdId").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
        verify(remoto, times(1)).crear(any(), eq("user-7"));
        verify(procesos, times(2)).guardar(argThat(p -> p.usuarioId().equals(7L)));
    }

    @Test void subDeOtroUsuarioNoPuedeUsarElLoginDeLaVictima() throws Exception {
        identidad();
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(8L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disponibilidad, procesos, remoto);
    }

    @Test void usuarioInactivoONoPersistidoNoPuedeCrearHold() throws Exception {
        when(usuarios.buscarPorLogin("prueba")).thenReturn(Optional.of(usuario(7L, false))).thenReturn(Optional.empty());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(disponibilidad, procesos, remoto);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"professionalId\":0,\"date\":\"2026-12-01\",\"startTime\":\"09:30\"}",
            "{\"professionalId\":15,\"date\":\"2026-13-01\",\"startTime\":\"09:30\"}",
            "{\"professionalId\":15,\"date\":\"2026-12-01\",\"startTime\":\"25:00\"}",
            "{\"professionalId\":15,\"date\":\"2026-12-01\"}"})
    void parametrosInvalidosSon400(String body) throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(disponibilidad, procesos, remoto);
    }

    @Test void conflictoPosteriorALaDisponibilidadEs409() throws Exception {
        identidad(); agendaYGuarda();
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.CONFLICTO));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_CONFLICTO"))
                .andExpect(jsonPath("$.detail").value("El horario solicitado no está disponible."))
                .andExpect(jsonPath("$.procesoId").value(42));
    }

    @Test void timeoutEs503ConReferenciaLocalNoExitoNiConfirmacion() throws Exception {
        identidad(); agendaYGuarda();
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.RESULTADO_INCIERTO));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("HOLD_RESULTADO_INCIERTO"))
                .andExpect(jsonPath("$.detail").value("No se conoce el resultado de la operación. Consulte su estado antes de volver a solicitarla."))
                .andExpect(jsonPath("$.procesoId").value(42));
        verify(remoto, times(1)).crear(any(), anyString());
    }
    @ParameterizedTest
    @CsvSource({"SOLICITUD_INVALIDA,400", "CREDENCIAL_TECNICA_RECHAZADA,502", "PROFESIONAL_INEXISTENTE,404", "CONFIGURACION_INCOMPLETA,502"})
    void erroresTecnicosNoSeConfundenConElJwtDelUsuario(CrearHoldException.Motivo motivo, int esperado) throws Exception {
        identidad(); agendaYGuarda();
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(motivo));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(esperado));
    }

    @ParameterizedTest
    @CsvSource({"SOLICITUD_INVALIDA,400", "PROFESIONAL_NO_ENCONTRADO,404", "NO_AUTENTICADO,401", "ACCESO_DENEGADO,403",
            "ERROR_SERVICIO,503", "TIEMPO_AGOTADO,503", "NO_DISPONIBLE,503", "RESPUESTA_INVALIDA,503"})
    void falloPrevioDeCatalogoNoEnviaPost(ConsultaCatalogoException.Motivo motivo, int esperado) throws Exception {
        identidad();
        when(disponibilidad.consultar(anyLong(), any())).thenThrow(new ConsultaCatalogoException(motivo));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(esperado));
        verifyNoInteractions(procesos, remoto);
    }

    @Test void falloDeOcupacionesNoCreaHoldSinDisponibilidadVerificada() throws Exception {
        identidad();
        when(disponibilidad.consultar(anyLong(), any())).thenThrow(
                new ConsultaOcupacionesException(ConsultaOcupacionesException.Motivo.TIEMPO_AGOTADO));
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(procesos, remoto);
    }
    @Test void duplicadoLocalDevuelve409SinPostNiMensajeDeFalloDePersistencia() throws Exception {
        identidad(); agendaYGuarda();
        doThrow(new CrearHoldException(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, null, 24L)).when(procesos).guardar(any());
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_SOLICITUD_DUPLICADA"))
                .andExpect(jsonPath("$.procesoId").value(24))
                .andExpect(jsonPath("$.detail").value("Ya existe una solicitud equivalente pendiente o un hold vigente."));
        verifyNoInteractions(remoto);
    }

    @Test void formatoInvalidoTieneMensajeDeValidacion() throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer " + token(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"professionalId\":15,\"date\":\"fecha-invalida\",\"startTime\":\"09:30\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Los datos de la solicitud no son válidos."));
        verifyNoInteractions(disponibilidad, procesos, remoto);
    }
}
