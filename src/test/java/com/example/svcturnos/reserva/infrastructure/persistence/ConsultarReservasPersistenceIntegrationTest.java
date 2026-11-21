package com.example.svcturnos.reserva.infrastructure.persistence;

import com.example.svcturnos.reserva.domain.exception.ConsultaReservasException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ConsultarReservasUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Testcontainers
@Transactional
class ConsultarReservasPersistenceIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired ConsultarReservasUseCase consultas;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean UsuarioAutenticadoPort usuario;
    @MockitoBean ConsultarReservasRemotasPort remoto;
    Long propio;
    Long ajeno;

    Long crearUsuario() {
        String login = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?, 'hash-de-prueba', 'Nombre', 'Apellido', ?) RETURNING id
                """, Long.class, login, login + "@test.invalid");
    }
    @BeforeEach void preparar() {
        propio = crearUsuario(); ajeno = crearUsuario();
        when(usuario.obtenerUsuarioId()).thenReturn(propio);
    }

    @Test void consultasPorPropietarioYDetalleConservanEstadosYPersistencia() {
        var pendiente = procesos.guardar(ProcesoReserva.crear(propio, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30)));
        var otro = procesos.guardar(ProcesoReserva.crear(ajeno, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(10, 0)));
        assertEquals(java.util.List.of(pendiente), consultas.consultarProcesos());
        assertEquals(pendiente, consultas.consultarProceso(pendiente.id()));
        Long idAjeno = otro.id();
        assertThrows(ConsultaReservasException.class, () -> consultas.consultarProceso(idAjeno));
        assertThrows(ConsultaReservasException.class, () -> consultas.consultarProceso(Long.MAX_VALUE));
        assertEquals(pendiente, procesos.buscarPorId(pendiente.id()).orElseThrow());
        assertEquals(EstadoProcesoReserva.CREADO, consultas.consultarProceso(pendiente.id()).estado());
        verifyNoInteractions(remoto);
    }

    @Test void usuarioSinProcesosDevuelveColeccionVacia() {
        procesos.guardar(ProcesoReserva.crear(ajeno, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30)));
        assertTrue(consultas.consultarProcesos().isEmpty());
    }
    @Test void consultaEnLotePermiteReservasSinProcesoYDetectaVinculosDeOtroUsuario() {
        var fecha = LocalDate.of(2026, 12, 1);
        var hora = LocalTime.of(9, 30);
        var ahora = Instant.parse("2026-11-20T10:00:00Z");
        var propioCreado = procesos.guardar(ProcesoReserva.crear(propio, 15L, fecha, hora));
        var ajenoCreado = procesos.guardar(ProcesoReserva.crear(ajeno, 15L, fecha, hora));
        var local = procesos.guardar(propioCreado.registrarHold("h-propio", "externo-propio", ahora.plusSeconds(600)));
        var otro = procesos.guardar(ajenoCreado.registrarHold("h-ajeno", "externo-ajeno", ahora.plusSeconds(600)));
        assertEquals(java.util.List.of(local, otro), procesos.buscarPorProcesosExternos(
                java.util.List.of("externo-propio", "externo-ajeno", "sin-local")));
        assertTrue(procesos.buscarPorProcesosExternos(java.util.List.of()).isEmpty());
        var filtros = new CriteriosConsultaReservas(null, null, null, 0, 20, java.util.List.of());
        String paciente = PacienteReserva.identificadorExterno(propio);
        var sinProceso = new ReservaCentral(8251L, "sin-local", paciente, fecha, hora, LocalTime.of(10, 0),
                15L, "Ana", "Gomez", EstadoReservaCentral.CONFIRMED, ahora, ahora, null);
        var pagina = new PaginaReservas(java.util.List.of(sinProceso), 1, 0, 20);
        when(remoto.consultar(paciente, filtros)).thenReturn(pagina);
        assertEquals(pagina, consultas.consultarReservas(filtros));
        var contradictoria = new ReservaCentral(8251L, "externo-ajeno", paciente, fecha, hora, LocalTime.of(10, 0),
                15L, "Ana", "Gomez", EstadoReservaCentral.CONFIRMED, ahora, ahora, null);
        when(remoto.consultar(paciente, filtros)).thenReturn(new PaginaReservas(java.util.List.of(contradictoria), 1, 0, 20));
        assertThrows(ConsultaReservasException.class, () -> consultas.consultarReservas(filtros));
        assertEquals(local, procesos.buscarPorId(local.id()).orElseThrow());
        assertEquals(otro, procesos.buscarPorId(otro.id()).orElseThrow());
    }

}
