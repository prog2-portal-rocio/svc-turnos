package com.example.svcturnos.reserva.infrastructure.persistence;

import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.CrearHoldUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import com.example.svcturnos.usuario.infrastructure.security.JwtTokenAdapter;
import com.example.svcturnos.usuario.domain.model.Usuario;
import org.springframework.http.MediaType;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CrearHoldPersistenceIntegrationTest.RelojFijo.class)
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Testcontainers
class CrearHoldPersistenceIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Autowired CrearHoldUseCase servicio;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean UsuarioAutenticadoPort usuario;
    @MockitoBean ConsultarDisponibilidadUseCase disponibilidad;
    @MockitoBean CrearHoldRemotoPort remoto;
    Long propietario;
    final SolicitudHold solicitud = new SolicitudHold(15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30));
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-18T14:55:00Z"), ZoneOffset.UTC);
    final Instant vence = RELOJ.instant().plusSeconds(300);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean @Primary Clock clockPrueba() { return RELOJ; }
    }

    @BeforeEach void preparar() {
        propietario = jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?, 'hash-prueba', 'Nombre', 'Apellido', ?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), UUID.randomUUID() + "@test.invalid");
        when(usuario.obtenerUsuarioId()).thenReturn(propietario);
        when(disponibilidad.consultar(15L, solicitud.date())).thenReturn(List.of(
                new BloqueHorario(solicitud.date(), solicitud.startTime(), LocalTime.of(10, 0))));
    }

    @Test void guardaLaIntencionAntesDeHttpYRecuperaResultadoRealDePostgres() {
        when(remoto.crear(solicitud, "user-" + propietario)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var pendientes = procesos.buscarPorUsuario(propietario);
            assertEquals(1, pendientes.size());
            assertEquals(EstadoProcesoReserva.HOLD_SOLICITADO, pendientes.getFirst().estado());
            return new HoldCreado("hold", "externo", vence);
        });
        var resultado = servicio.crear(solicitud);
        var recuperado = procesos.buscarPorId(resultado.id()).orElseThrow();
        assertEquals(resultado, recuperado);
        assertEquals(propietario, recuperado.usuarioId());
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, recuperado.estado());
        assertEquals(vence, recuperado.vencimientoHold());
        assertEquals("hold", recuperado.holdId());
        assertEquals("externo", recuperado.procesoExternoId());
        assertEquals(1L, recuperado.version());
    }

    @Test void timeoutConservaUnIntentoInciertoTrasReleerDesdeLaBase() {
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.RESULTADO_INCIERTO));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        var pendiente = procesos.buscarPorId(error.getProcesoId()).orElseThrow();
        assertEquals(EstadoProcesoReserva.HOLD_SOLICITADO, pendiente.estado());
        assertNull(pendiente.holdId());
        verify(remoto, times(1)).crear(solicitud, "user-" + propietario);
    }

    @Test void rechazoDefinitivoQuedaDiferenciadoDeUnTimeout() {
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.CONFLICTO));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(EstadoProcesoReserva.HOLD_RECHAZADO, procesos.buscarPorId(error.getProcesoId()).orElseThrow().estado());
    }

    @Test void rollbackDeTransaccionDelLlamadorNoBorraElIntentoExterno() {
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.RESULTADO_INCIERTO));
        var tx = new TransactionTemplate(transactions);
        assertThrows(CrearHoldException.class, () -> tx.execute(status -> servicio.crear(solicitud)));
        assertEquals(EstadoProcesoReserva.HOLD_SOLICITADO, procesos.buscarPorUsuario(propietario).getFirst().estado());
    }

    @Test void falloAlPersistirResultadoNoBorraLaIntencionNiRepiteElPost() {
        // Un trigger de prueba simula pérdida de escritura de progreso solo en este propietario del contenedor.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION simular_fallo_hold() RETURNS trigger AS $$
                BEGIN
                    IF NEW.estado = 'HOLD_REGISTRADO' THEN RAISE EXCEPTION 'fallo simulado'; END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """);
        String trigger = "fallo_hold_" + propietario;
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON procesos_reserva FOR EACH ROW WHEN (NEW.usuario_id = "
                + propietario + ") EXECUTE FUNCTION simular_fallo_hold()");
        when(remoto.crear(any(), anyString())).thenReturn(new HoldCreado("hold", "externo", vence));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(CrearHoldException.Motivo.PERSISTENCIA_FALLIDA, error.getMotivo());
        assertEquals(EstadoProcesoReserva.HOLD_SOLICITADO, procesos.buscarPorId(error.getProcesoId()).orElseThrow().estado());
        verify(remoto, times(1)).crear(solicitud, "user-" + propietario);
    }
    @Test void actualizacionObsoletaNoPuedeSobrescribirUnHoldRegistrado() {
        var pendiente = procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime()).solicitarHold());
        var registrado = procesos.guardar(pendiente.registrarHold("hold", "externo", vence));
        var rechazoObsoleto = pendiente.rechazarHold();
        assertThrows(com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException.class,
                () -> procesos.guardar(rechazoObsoleto));
        assertEquals(registrado, procesos.buscarPorId(pendiente.id()).orElseThrow());
    }
    @Test void solicitudesConsecutivasNoRepitenUnHoldVigente() {
        when(remoto.crear(any(), anyString())).thenReturn(new HoldCreado("hold", "externo", vence));
        var primero = servicio.crear(solicitud);
        var duplicado = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, duplicado.getMotivo());
        assertEquals(primero.id(), duplicado.getProcesoId());
        assertEquals(1, procesos.buscarPorUsuario(propietario).size());
        verify(remoto, times(1)).crear(solicitud, "user-" + propietario);
    }

    @Test void resultadoInciertoImpideNuevaSolicitudAunqueElUsuarioLaRepita() {
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.RESULTADO_INCIERTO));
        var primero = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        var duplicado = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, duplicado.getMotivo());
        assertEquals(primero.getProcesoId(), duplicado.getProcesoId());
        assertEquals(EstadoProcesoReserva.HOLD_SOLICITADO, procesos.buscarPorId(primero.getProcesoId()).orElseThrow().estado());
        assertEquals(1, procesos.buscarPorUsuario(propietario).size());
        verify(remoto, times(1)).crear(solicitud, "user-" + propietario);
    }

    @Test void intentoPrevioSolicitadoNoEnviaNingunPostNuevo() {
        var previo = procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime()).solicitarHold());
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, error.getMotivo());
        assertEquals(previo.id(), error.getProcesoId());
        verifyNoInteractions(remoto);
    }

    @Test void holdRegistradoAnteriorTambienImpideNuevoPost() {
        var previo = procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime())
                .registrarHold("hold-previo", "externo-previo", vence));
        var error = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        assertEquals(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, error.getMotivo());
        assertEquals(previo.id(), error.getProcesoId());
        verifyNoInteractions(remoto);
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0})
    void holdVencidoPermiteProcesoNuevoSinModificarElAnterior(long segundos) {
        var previo = procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime())
                .registrarHold("hold-vencido", "externo-previo", RELOJ.instant().plusSeconds(segundos)));
        when(remoto.crear(any(), anyString())).thenReturn(new HoldCreado("hold-nuevo", "externo-nuevo", vence));
        var nuevo = servicio.crear(solicitud);
        assertNotEquals(previo.id(), nuevo.id());
        assertEquals(previo, procesos.buscarPorId(previo.id()).orElseThrow());
        assertEquals(2, procesos.buscarPorUsuario(propietario).size());
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, nuevo.estado());
        verify(remoto, times(1)).crear(solicitud, "user-" + propietario);
    }

    @Test void rechazoDefinitivoPermiteOtroIntentoExplicitoConservandoHistoria() {
        when(remoto.crear(any(), anyString())).thenThrow(new CrearHoldException(CrearHoldException.Motivo.CONFLICTO))
                .thenReturn(new HoldCreado("hold-nuevo", "externo-nuevo", vence));
        var rechazo = assertThrows(CrearHoldException.class, () -> servicio.crear(solicitud));
        var nuevo = servicio.crear(solicitud);
        assertNotEquals(rechazo.getProcesoId(), nuevo.id());
        assertEquals(EstadoProcesoReserva.HOLD_RECHAZADO, procesos.buscarPorId(rechazo.getProcesoId()).orElseThrow().estado());
        assertEquals(2, procesos.buscarPorUsuario(propietario).size());
        verify(remoto, times(2)).crear(solicitud, "user-" + propietario);
    }

    @Test void dosPeticionesHttpConcurrentesSoloGeneranUnPostYUnProceso() throws Exception {
        var barrera = new CyclicBarrier(2);
        when(disponibilidad.consultar(15L, solicitud.date())).thenAnswer(invocation -> {
            barrera.await(10, TimeUnit.SECONDS);
            return List.of(new BloqueHorario(solicitud.date(), solicitud.startTime(), LocalTime.of(10, 0)));
        });
        when(remoto.crear(any(), anyString())).thenReturn(new HoldCreado("hold", "externo", vence));
        String token = new JwtTokenAdapter(encoder).generarToken(new Usuario(propietario, "usuario-prueba", "hash-prueba",
                "Nombre", "Apellido", "prueba@test.invalid", null, "es", true));
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> peticion = () -> mvc.perform(post("/api/reservas/holds")
                    .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"professionalId\":15,\"date\":\"2026-12-01\",\"startTime\":\"09:30\"}"))
                    .andReturn().getResponse().getStatus();
            var primera = executor.submit(peticion);
            var segunda = executor.submit(peticion);
            var estados = List.of(primera.get(20, TimeUnit.SECONDS), segunda.get(20, TimeUnit.SECONDS));
            assertEquals(List.of(201, 409), estados.stream().sorted().toList());
        }
        assertEquals(1, procesos.buscarPorUsuario(propietario).size());
        verify(remoto, times(1)).crear(solicitud, "user-" + propietario);
    }

    @Test void solicitudesDeDistintosUsuariosNoSeBloqueanLocalmente() {
        procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime()).solicitarHold());
        Long otroUsuario = jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?, 'hash-prueba', 'Otro', 'Usuario', ?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), UUID.randomUUID() + "@test.invalid");
        when(usuario.obtenerUsuarioId()).thenReturn(otroUsuario);
        when(remoto.crear(any(), eq("user-" + otroUsuario))).thenReturn(new HoldCreado("hold-otro", "externo-otro", vence));
        var resultado = servicio.crear(solicitud);
        assertEquals(otroUsuario, resultado.usuarioId());
        assertEquals(1, procesos.buscarPorUsuario(propietario).size());
        assertEquals(1, procesos.buscarPorUsuario(otroUsuario).size());
        verify(remoto, times(1)).crear(solicitud, "user-" + otroUsuario);
    }
    @Test void activarUnProcesoCreadoTambienRespetaLaProteccionDeDuplicados() {
        var creado = procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime()));
        var pendiente = procesos.guardar(ProcesoReserva.crear(propietario, 15L, solicitud.date(), solicitud.startTime()).solicitarHold());
        var activado = creado.solicitarHold();
        var error = assertThrows(CrearHoldException.class, () -> procesos.guardar(activado));
        assertEquals(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, error.getMotivo());
        assertEquals(pendiente.id(), error.getProcesoId());
        assertEquals(EstadoProcesoReserva.CREADO, procesos.buscarPorId(creado.id()).orElseThrow().estado());
        verifyNoInteractions(remoto);
    }
}
