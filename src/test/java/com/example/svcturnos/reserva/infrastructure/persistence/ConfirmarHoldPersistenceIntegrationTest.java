package com.example.svcturnos.reserva.infrastructure.persistence;

import com.example.svcturnos.reserva.domain.exception.*;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.kafka.EventoReservaDecoder;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;

@SpringBootTest
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Testcontainers
@Import(ConfirmarHoldPersistenceIntegrationTest.RelojFijo.class)
class ConfirmarHoldPersistenceIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), ZoneOffset.UTC);
    static final AtomicLong OFFSET = new AtomicLong(10000);
    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean @Primary Clock clockPrueba() { return RELOJ; }
    }
    @Autowired ConfirmarHoldUseCase servicio;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired InboxReservasPort inbox;
    @Autowired RecibirEventoReservaUseCase recibir;
    @Autowired EventoReservaDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean UsuarioAutenticadoPort usuario;
    @MockitoBean ConfirmarHoldRemotoPort remoto;
    Long propietario;
    Long procesoId;
    PacienteReserva paciente;
    ProcesoReserva hold;
    final Instant vence = RELOJ.instant().plusSeconds(300);

    @BeforeEach void preparar() {
        propietario = jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?, 'hash-prueba', 'Juan', 'Perez', ?) RETURNING id
                """, Long.class, UUID.randomUUID().toString(), UUID.randomUUID() + "@test.invalid");
        paciente = new PacienteReserva(propietario, "Juan", "Perez");
        when(usuario.obtenerPaciente()).thenReturn(paciente);
        hold = procesos.guardar(new ProcesoReserva(null, propietario, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30),
                EstadoProcesoReserva.HOLD_REGISTRADO, "hold-" + propietario, "externo-" + propietario, vence,
                RELOJ.instant().minusSeconds(60), RELOJ.instant(), null));
        procesoId = hold.id();
    }

    ConfirmacionInicialAceptada respuesta() { return new ConfirmacionInicialAceptada(hold.procesoExternoId(), vence); }

    @Test void intencionComprometidaAntesDelPostYProgresoAceptadoNoEsConfirmacionFinal() {
        when(remoto.confirmar(hold.holdId(), paciente)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(EstadoProcesoReserva.CONFIRMACION_SOLICITADA, procesos.buscarPorId(hold.id()).orElseThrow().estado());
            return respuesta();
        });
        var aceptado = servicio.confirmar(procesoId);
        assertEquals(aceptado, procesos.buscarPorId(hold.id()).orElseThrow());
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, aceptado.estado());
        assertEquals(2L, aceptado.version());
        assertEquals(vence, aceptado.vencimientoHold());
        assertEquals(aceptado, servicio.confirmar(procesoId));
        verify(remoto, times(1)).confirmar(hold.holdId(), paciente);
    }

    @Test void dosConfirmacionesSimultaneasSoloEnvianUnPost() throws Exception {
        var llego = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        when(remoto.confirmar(hold.holdId(), paciente)).thenAnswer(invocation -> {
            llego.countDown();
            assertTrue(liberar.await(10, TimeUnit.SECONDS));
            return respuesta();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primera = executor.submit(() -> servicio.confirmar(procesoId));
            try {
                assertTrue(llego.await(10, TimeUnit.SECONDS));
                var segunda = executor.submit(() -> assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(procesoId)));
                assertEquals(ConfirmarHoldException.Motivo.ESTADO_INCOMPATIBLE, segunda.get(10, TimeUnit.SECONDS).getMotivo());
            } finally { liberar.countDown(); }
            assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, primera.get(10, TimeUnit.SECONDS).estado());
        }
        verify(remoto, times(1)).confirmar(hold.holdId(), paciente);
    }

    @Test void dosActualizacionesLeidasDeLaMismaVersionSoloUnaIniciaLaConfirmacion() throws Exception {
        var barrera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> iniciar = () -> {
                var anterior = procesos.buscarPorId(hold.id()).orElseThrow();
                barrera.await(10, TimeUnit.SECONDS);
                try {
                    procesos.guardar(anterior.solicitarConfirmacion(RELOJ.instant()));
                    return true;
                } catch (ProcesoReservaConcurrenteException _) { return false; }
            };
            var uno = executor.submit(iniciar);
            var dos = executor.submit(iniciar);
            assertEquals(List.of(false, true), List.of(uno.get(10, TimeUnit.SECONDS), dos.get(10, TimeUnit.SECONDS)).stream().sorted().toList());
        }
    }

    @Test void perdidaDeRespuestaNoRepitePostYConservaIdsParaReconciliacion() {
        when(remoto.confirmar(any(), any())).thenThrow(new ConfirmarHoldException(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO));
        var id = hold.id();
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(id));
        var incierto = procesos.buscarPorId(id).orElseThrow();
        assertEquals(EstadoProcesoReserva.CONFIRMACION_INCIERTA, incierto.estado());
        assertEquals(hold.holdId(), incierto.holdId());
        assertEquals(hold.procesoExternoId(), incierto.procesoExternoId());
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(id));
        verify(remoto, times(1)).confirmar(hold.holdId(), paciente);
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class, names = {"CONFIRMACION_INCIERTA", "CONFIRMACION_SOLICITADA"})
    void solicitudVigenteRecuperaConfirmacionPersistidaSinOtroPost(EstadoProcesoReserva estado) {
        var iniciado = procesos.guardar(hold.solicitarConfirmacion(RELOJ.instant()));
        if (estado == EstadoProcesoReserva.CONFIRMACION_INCIERTA) {
            procesos.guardar(iniciado.registrarResultadoConfirmacion(estado, RELOJ.instant()));
        }
        var json = solicitud(UUID.randomUUID().toString(), hold.procesoExternoId(), hold.holdId())
                .replace("2026-09-10T14:44:50Z", RELOJ.instant().plusSeconds(200).toString())
                .replace("2026-09-10T14:35:10Z", RELOJ.instant().toString());
        var evento = recibir.recibir(decoder.decodificar(mensaje(json, hold.procesoExternoId(), OFFSET.incrementAndGet())));
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, evento.estado());
        var recuperado = procesos.buscarPorId(procesoId).orElseThrow();
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, recuperado.estado());
        assertEquals(RELOJ.instant().plusSeconds(200), recuperado.vencimientoHold());
        assertNull(recuperado.reservaId());
        assertEquals(recuperado, servicio.confirmar(procesoId));
        assertEquals(recuperado.vencimientoHold(), SolicitudEnvioInformacion.validar(recuperado, propietario, evento, RELOJ.instant()));
        verifyNoInteractions(remoto);
    }

    @Test void falloPostgresPosteriorAl202ConservaIntencionYBloqueaOtroPost() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fallo_confirmacion() RETURNS trigger AS $$
                BEGIN
                    IF NEW.estado = 'ESPERANDO_TELEFONO' THEN RAISE EXCEPTION 'fallo simulado'; END IF;
                    RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER fallo_confirmacion_" + propietario
                + " BEFORE UPDATE ON procesos_reserva FOR EACH ROW WHEN (NEW.usuario_id = " + propietario
                + ") EXECUTE FUNCTION fallo_confirmacion()");
        when(remoto.confirmar(any(), any())).thenReturn(respuesta());
        var id = hold.id();
        var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(id));
        assertEquals(ConfirmarHoldException.Motivo.PERSISTENCIA_FALLIDA, error.getMotivo());
        assertEquals(EstadoProcesoReserva.CONFIRMACION_SOLICITADA, procesos.buscarPorId(id).orElseThrow().estado());
        assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(id));
        verify(remoto, times(1)).confirmar(hold.holdId(), paciente);
    }

    @Test void eventoVigenteDuranteElPostRecuperaLaAceptacionInicial() {
        String eventId = UUID.randomUUID().toString();
        var json = solicitud(eventId, hold.procesoExternoId(), hold.holdId())
                .replace("2026-09-10T14:44:50Z", RELOJ.instant().plusSeconds(200).toString())
                .replace("2026-09-10T14:35:10Z", RELOJ.instant().toString());
        var recepcion = decoder.decodificar(mensaje(json,
                hold.procesoExternoId(), OFFSET.incrementAndGet()));
        when(remoto.confirmar(any(), any())).thenAnswer(invocation -> {
            var evento = recibir.recibir(recepcion);
            assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, evento.estado());
            assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, procesos.buscarPorId(hold.id()).orElseThrow().estado());
            return respuesta();
        });
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, servicio.confirmar(procesoId).estado());
        var duplicado = recibir.recibir(decoder.decodificar(mensaje(recepcion.json(), hold.procesoExternoId(), OFFSET.incrementAndGet())));
        assertEquals(EstadoEventoReserva.DUPLICADO, duplicado.estado());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eventos_reserva_inbox WHERE event_id=? AND canonico", Integer.class, eventId));
    }

    @Test void confirmacionRecuperaSolicitudAdelantadaSinProcesoYConservaRequestEventId() {
        String eventId = UUID.randomUUID().toString();
        // Se simula una recepción adelantada, cuando la transacción REST aún no había publicado los identificadores.
        var evento = inbox.conservar(decoder.decodificar(mensaje(solicitud(eventId, hold.procesoExternoId(), hold.holdId()),
                hold.procesoExternoId(), OFFSET.incrementAndGet())));
        inbox.registrarCorrelacion(evento.id(), new CorrelacionEvento(EstadoEventoReserva.PENDIENTE_CORRELACION, null));
        when(remoto.confirmar(any(), any())).thenReturn(respuesta());
        servicio.confirmar(procesoId);
        var recuperado = inbox.buscarPorId(evento.id()).orElseThrow();
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, recuperado.estado());
        assertEquals(eventId, recuperado.evento().eventId());
        assertEquals(hold.id(), recuperado.procesoLocalId());
    }

    @ParameterizedTest @EnumSource(value = EstadoProcesoReserva.class, names = {"CONFIRMACION_SOLICITADA", "CONFIRMACION_INCIERTA", "ESPERANDO_TELEFONO"})
    void nuevoHoldEquivalenteSigueBloqueadoDuranteLaConfirmacion(EstadoProcesoReserva estado) {
        var anterior = procesos.guardar(hold.solicitarConfirmacion(RELOJ.instant()));
        if (estado != EstadoProcesoReserva.CONFIRMACION_SOLICITADA) {
            anterior = procesos.guardar(estado == EstadoProcesoReserva.ESPERANDO_TELEFONO
                    ? anterior.registrarConfirmacion(RELOJ.instant().minusSeconds(1), RELOJ.instant())
                    : anterior.registrarResultadoConfirmacion(estado, RELOJ.instant()));
        }
        var nuevo = new ProcesoReserva(null, propietario, 15L, hold.fecha(), hold.hora(), EstadoProcesoReserva.HOLD_SOLICITADO,
                null, null, null, RELOJ.instant(), RELOJ.instant(), null);
        var error = assertThrows(CrearHoldException.class, () -> procesos.guardar(nuevo));
        assertEquals(CrearHoldException.Motivo.SOLICITUD_DUPLICADA, error.getMotivo());
        assertEquals(anterior.id(), error.getProcesoId());
    }

    @Test void migracionDesdeV5ConservaProcesosEInboxExistentes() throws Exception {
        var config = org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("upgrade_v5_confirmacion").defaultSchema("upgrade_v5_confirmacion");
        config.target("5").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO upgrade_v5_confirmacion.usuarios(login,password_hash,first_name,last_name,email)
                    VALUES ('previo','hash-prueba','Juan','Perez','previo@test.invalid')
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v5_confirmacion.procesos_reserva(usuario_id,profesional_id,fecha,hora,estado,
                        hold_id,proceso_externo_id,vencimiento_hold,creado_en,actualizado_en)
                    SELECT id,15,DATE '2026-12-01',TIME '09:30','HOLD_REGISTRADO','hold-previo','externo-previo',
                        TIMESTAMPTZ '2026-12-01T15:00:00Z',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    FROM upgrade_v5_confirmacion.usuarios
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v5_confirmacion.eventos_reserva_inbox(topic,particion,offset_mensaje,
                        event_id,event_id_hash,event_type,proceso_externo_id,hold_id,occurred_at,schema_version,
                        payload_json,canonico,estado,required_field,mensaje,expires_at)
                    VALUES ('acciones-previas',0,1,'request-previo',decode(repeat('00',32),'hex'),
                        'AdditionalInformationRequested','externo-previo','hold-previo',CURRENT_TIMESTAMP,1,
                        '{}',true,'PENDIENTE_CORRELACION','PHONE_NUMBER','Ingrese telefono',CURRENT_TIMESTAMP)
                    """);
            config.target(org.flywaydb.core.api.MigrationVersion.LATEST).load().migrate();
            try (var result = statement.executeQuery("SELECT event_id,estado,hold_id FROM upgrade_v5_confirmacion.eventos_reserva_inbox")) {
                assertTrue(result.next());
                assertEquals("request-previo", result.getString(1));
                assertEquals("PENDIENTE_CORRELACION", result.getString(2));
                assertEquals("hold-previo", result.getString(3));
                assertFalse(result.next());
            }
            try (var result = statement.executeQuery("SELECT estado,hold_id FROM upgrade_v5_confirmacion.procesos_reserva")) {
                assertTrue(result.next());
                assertEquals("HOLD_REGISTRADO", result.getString(1));
                assertEquals("hold-previo", result.getString(2));
            }
            statement.executeUpdate("UPDATE upgrade_v5_confirmacion.procesos_reserva SET estado='CONFIRMACION_SOLICITADA'");
        }
    }
    @ParameterizedTest @ValueSource(longs = {-1, 0, 200, 301})
    void persisteEl202InclusoVencidoSinHabilitarReenvios(long segundos) {
        var ahora = RELOJ.instant();
        when(remoto.confirmar(hold.holdId(), paciente))
                .thenReturn(new ConfirmacionInicialAceptada(hold.procesoExternoId(), ahora.plusSeconds(segundos)));
        if (segundos > 0) {
            assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, servicio.confirmar(procesoId).estado());
            servicio.confirmar(procesoId);
        } else {
            var error = assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(procesoId));
            assertEquals(ConfirmarHoldException.Motivo.HOLD_VENCIDO, error.getMotivo());
            assertThrows(ConfirmarHoldException.class, () -> servicio.confirmar(procesoId));
        }
        var guardado = procesos.buscarPorId(procesoId).orElseThrow();
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, guardado.estado());
        assertEquals(ahora.plusSeconds(Math.min(segundos, 300)), guardado.vencimientoHold());
        assertEquals(hold.procesoExternoId(), guardado.procesoExternoId());
        assertEquals(hold.holdId(), guardado.holdId());
        assertTrue(guardado.impideNuevoHold(ahora.plusSeconds(1000)));
        verify(remoto, times(1)).confirmar(hold.holdId(), paciente);
    }

}
