package com.example.svcturnos.reserva.infrastructure.web;

import com.example.svcturnos.reserva.application.service.ConsultarReservasService;
import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.security.UsuarioAutenticadoAdapter;
import com.example.svcturnos.reserva.infrastructure.web.controller.ConsultaReservasController;
import com.example.svcturnos.reserva.infrastructure.web.handler.ConsultaReservasExceptionHandler;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConsultaReservasController.class)
@Import({SecurityConfig.class, JwtConfig.class, ConsultarReservasService.class,
        UsuarioAutenticadoAdapter.class, ConsultaReservasExceptionHandler.class})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
class ConsultaReservasControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean UsuarioPersistencePort usuarios;
    @MockitoBean ProcesoReservaPersistencePort procesos;
    @MockitoBean ConsultarReservasRemotasPort remoto;
    static final Instant AHORA = Instant.parse("2026-11-20T10:00:00Z");
    static final LocalDate FECHA = LocalDate.of(2026, 12, 1);
    static final LocalTime HORA = LocalTime.of(9, 30);

    Usuario usuario(Long id, boolean activo) {
        return new Usuario(id, "usuario" + id, "hash-prueba", "Nombre", "Apellido", "u" + id + "@test.invalid", null, "es", activo);
    }
    String token(Long id) { return new JwtTokenAdapter(encoder).generarToken(usuario(id, true)); }
    ProcesoReserva proceso(Long propietario) {
        return new ProcesoReserva(42L, propietario, 15L, FECHA, HORA, EstadoProcesoReserva.ESPERANDO_TELEFONO,
                "hold", "externo", AHORA.plusSeconds(600), AHORA, AHORA, 0L);
    }
    @BeforeEach void identidad() {
        when(usuarios.buscarPorLogin("usuario7")).thenReturn(Optional.of(usuario(7L, true)));
        when(usuarios.buscarPorLogin("usuario8")).thenReturn(Optional.of(usuario(8L, true)));
        when(procesos.buscarPorUsuario(7L)).thenReturn(List.of());
    }

    @ParameterizedTest @ValueSource(strings = {"/api/procesos-reserva", "/api/procesos-reserva/42", "/api/reservas"})
    void sinJWTOTokenInvalidoEs401(String ruta) throws Exception {
        mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
        mvc.perform(get(ruta).header("Authorization", "Bearer invalido")).andExpect(status().isUnauthorized());
        verifyNoInteractions(procesos, remoto);
    }

    @Test void propietarioProcedeDelUsuarioPersistidoYNoDeParametrosHTTP() throws Exception {
        when(procesos.buscarPorUsuario(7L)).thenReturn(List.of(proceso(7L)));
        mvc.perform(get("/api/procesos-reserva").param("usuarioId", "8").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].procesoId").value(42))
                .andExpect(jsonPath("$[0].estado").value("ESPERANDO_TELEFONO"))
                .andExpect(jsonPath("$[0].procesoExternoId").value("externo"))
                .andExpect(jsonPath("$[0].reservaId").isEmpty())
                .andExpect(jsonPath("$[0].usuarioId").doesNotExist())
                .andExpect(jsonPath("$[0].version").doesNotExist());
        verify(procesos, never()).buscarPorUsuario(8L);
        verifyNoInteractions(remoto);
    }

    @Test void detalleAjenoSeRespondeIgualQueUnoInexistente() throws Exception {
        when(procesos.buscarPorId(42L)).thenReturn(Optional.of(proceso(7L)));
        mvc.perform(get("/api/procesos-reserva/42").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ESPERANDO_TELEFONO"));
        mvc.perform(get("/api/procesos-reserva/42").header("Authorization", "Bearer " + token(8L)))
                .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/procesos-reserva/999").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isNotFound());
    }

    @Test void listadoVacioValidoYUsuarioInactivoDenegado() throws Exception {
        mvc.perform(get("/api/procesos-reserva").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        when(usuarios.buscarPorLogin("usuario7")).thenReturn(Optional.of(usuario(7L, false)));
        mvc.perform(get("/api/procesos-reserva").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isForbidden());
    }

    @Test void reservasCentralesPaginadasNoAceptanPacienteArbitrarioNiExponenDatosPrivados() throws Exception {
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of(proceso(7L)));
        var criterios = new CriteriosConsultaReservas(EstadoReservaCentral.CONFIRMED, FECHA, FECHA, 1, 10,
                List.of("appointmentDate,desc", "startTime,asc"));
        var reserva = new ReservaCentral(8251L, "externo", "user-7", FECHA, HORA, LocalTime.of(10, 0),
                15L, "Ana", "Gomez", EstadoReservaCentral.CONFIRMED, AHORA, AHORA, null);
        when(remoto.consultar("user-7", criterios)).thenReturn(new PaginaReservas(List.of(reserva), 11, 1, 10));
        mvc.perform(get("/api/reservas").header("Authorization", "Bearer " + token(7L))
                        .param("externalPatientId", "user-8").param("from", "2026-12-01").param("to", "2026-12-01")
                        .param("page", "1").param("size", "10").param("sort", "appointmentDate,desc", "startTime,asc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.content[0].reservationId").value(8251))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.content[0].externalPatientId").doesNotExist())
                .andExpect(jsonPath("$.content[0].patientPhone").doesNotExist());
        verify(remoto).consultar("user-7", criterios);
        verify(procesos, never()).guardar(any());
    }

    @ParameterizedTest @CsvSource({"page,-1", "size,0", "size,101", "status,CONFIRMADA", "from,2026-02-30",
            "to,fecha", "sort,patientPhone", "page,texto"})
    void parametrosInvalidosSon400SinConsultaExterna(String parametro, String valor) throws Exception {
        mvc.perform(get("/api/reservas").param(parametro, valor).header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(remoto);
    }

    @Test void rangoInvertidoEIdentificadoresInvalidosSon400() throws Exception {
        mvc.perform(get("/api/reservas").param("from", "2026-12-02").param("to", "2026-12-01")
                .header("Authorization", "Bearer " + token(7L))).andExpect(status().isBadRequest());
        for (String id : List.of("0", "-1", "abc")) {
            mvc.perform(get("/api/procesos-reserva/" + id).header("Authorization", "Bearer " + token(7L)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @CsvSource({"CONFIGURACION_INCOMPLETA,502", "CREDENCIAL_TECNICA_RECHAZADA,502",
            "SOLICITUD_RECHAZADA,400", "TIMEOUT,504", "CONEXION_FALLIDA,503", "SERVICIO_NO_DISPONIBLE,503",
            "RESPUESTA_INVALIDA,502"})
    void erroresRemotosNoSonPaginasVacias(ConsultaReservasException.Motivo motivo, int status) throws Exception {
        when(remoto.consultar(eq("user-7"), any())).thenThrow(new ConsultaReservasException(motivo));
        mvc.perform(get("/api/reservas").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.code").value("CONSULTA_" + motivo));
    }
    @Test void paginaCentralVaciaConservaLosDefaultsDocumentados() throws Exception {
        var criterios = new CriteriosConsultaReservas(null, null, null, 0, 20, List.of());
        when(remoto.consultar("user-7", criterios)).thenReturn(new PaginaReservas(List.of(), 0, 0, 20));
        mvc.perform(get("/api/reservas").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test void usuarioNoPersistidoYFalloDeBaseNoDanUnaConsultaExitosa() throws Exception {
        when(usuarios.buscarPorLogin("usuario7")).thenReturn(Optional.empty());
        mvc.perform(get("/api/procesos-reserva").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isForbidden());
        when(usuarios.buscarPorLogin("usuario7")).thenReturn(Optional.of(usuario(7L, true)));
        when(procesos.buscarPorUsuario(7L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("detalle privado"));
        mvc.perform(get("/api/procesos-reserva").header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PERSISTENCIA_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.detail").value("No se pudo completar la consulta en las condiciones actuales."));
    }

    @Test void reservaCentralPropiaSinProcesoLocalDevuelve200ConTotalOriginal() throws Exception {
        var criterios = new CriteriosConsultaReservas(null, null, null, 1, 20, List.of());
        var reserva = new ReservaCentral(8251L, "externo", "user-7", FECHA, HORA, LocalTime.of(10, 0),
                15L, "Ana", "Gomez", EstadoReservaCentral.CONFIRMED, AHORA, AHORA, null);
        when(remoto.consultar("user-7", criterios)).thenReturn(new PaginaReservas(List.of(reserva), 21, 1, 20));
        when(procesos.buscarPorProcesosExternos(List.of("externo"))).thenReturn(List.of());
        mvc.perform(get("/api/reservas").param("page", "1").param("externalPatientId", "user-8")
                        .header("Authorization", "Bearer " + token(7L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].reservationId").value(8251))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalElements").value(21)).andExpect(jsonPath("$.page").value(1));
        verify(procesos, never()).guardar(any());
    }

}
