package com.example.svcturnos.reserva.infrastructure.persistence;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.exception.ProcesoReservaConcurrenteException;
import com.example.svcturnos.reserva.domain.exception.ProcesoReservaNoActualizableException;
import com.example.svcturnos.reserva.domain.ports.out.ProcesoReservaPersistencePort;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Testcontainers
class ProcesoReservaPersistenceIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    Long usuario;

    @BeforeEach
    void preparar() {
        usuario = jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?, 'hash-de-prueba', 'Nombre', 'Apellido', ?) RETURNING id
                """, Long.class, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID() + "@test.invalid");
    }

    private ProcesoReserva nuevo(Long propietario) {
        return ProcesoReserva.crear(propietario, 15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30));
    }

    @Test
    void persisteConsultaPorPropietarioYConservaProgreso() {
        var p = procesos.guardar(nuevo(usuario));
        assertEquals(p, procesos.buscarPorId(p.id()).orElseThrow());
        assertEquals(0L, p.version());
        assertTrue(procesos.buscarPorId(Long.MAX_VALUE).isEmpty());
        assertTrue(procesos.buscarPorUsuario(Long.MAX_VALUE).isEmpty());
        var segundo = procesos.guardar(nuevo(usuario));
        assertEquals(2, procesos.buscarPorUsuario(usuario).size());
        Instant vence = Instant.parse("2026-12-01T09:35:00Z");
        var actualizado = procesos.guardar(p.registrarHold("hold-opaco", "proceso-opaco", vence));
        assertEquals(1L, actualizado.version());
        assertEquals(actualizado, procesos.buscarPorId(p.id()).orElseThrow());
        assertEquals(vence, actualizado.vencimientoHold());
        assertEquals("hold-opaco", actualizado.holdId());
        assertEquals("proceso-opaco", actualizado.procesoExternoId());
        assertEquals(EstadoProcesoReserva.CREADO, procesos.buscarPorId(segundo.id()).orElseThrow().estado());
    }

    @Test
    void exigeUsuarioExistenteYRevierteTransaccionFallida() {
        var invalido = nuevo(Long.MAX_VALUE);
        assertThrows(DataIntegrityViolationException.class, () -> procesos.guardar(invalido));
        var tx = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            procesos.guardar(nuevo(usuario));
            throw new IllegalStateException("fallo simulado");
        }));
        assertTrue(procesos.buscarPorUsuario(usuario).isEmpty());
    }

    @Test
    void noPermiteReasignarPropietarioNiRetrocederEstado() {
        var p = procesos.guardar(nuevo(usuario));
        var otro = new ProcesoReserva(p.id(), Long.MAX_VALUE, p.profesionalId(), p.fecha(), p.hora(),
                p.estado(), null, null, null, p.creadoEn(), p.actualizadoEn(), p.version());
        assertThrows(ProcesoReservaNoActualizableException.class, () -> procesos.guardar(otro));
        var avanzado = procesos.guardar(p.registrarHold("hold", "externo", Instant.now()));
        var retroceso = new ProcesoReserva(p.id(), p.usuarioId(), p.profesionalId(), p.fecha(), p.hora(),
                EstadoProcesoReserva.CREADO, null, null, null, p.creadoEn(), avanzado.actualizadoEn(), avanzado.version());
        assertThrows(ProcesoReservaNoActualizableException.class, () -> procesos.guardar(retroceso));
        assertEquals(avanzado, procesos.buscarPorId(p.id()).orElseThrow());
    }

    @Test
    void rechazaDosActualizacionesConcurrentesSinPerderLaPrimera() throws Exception {
        var p = procesos.guardar(nuevo(usuario));
        var progreso = p.registrarHold("hold", "externo", Instant.parse("2026-12-01T09:35:00Z"));
        var barrera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> actualizar = () -> {
                barrera.await(10, TimeUnit.SECONDS);
                try {
                    procesos.guardar(progreso);
                    return true;
                } catch (ProcesoReservaConcurrenteException _) {
                    return false;
                }
            };
            var a = executor.submit(actualizar);
            var b = executor.submit(actualizar);
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1L, procesos.buscarPorId(p.id()).orElseThrow().version());
    }

    @Test
    void migraUnaBaseV1QueYaContieneUsuarios() throws Exception {
        String schema = "upgrade_procesos";
        var configuracion = org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema);
        configuracion.target("1").load().migrate();
        try (var conexion = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var statement = conexion.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO upgrade_procesos.usuarios(login,password_hash,first_name,last_name,email)
                    VALUES ('existente', 'hash-prueba', 'Nombre', 'Apellido', 'existente@test.invalid')
                    """);
            org.flywaydb.core.Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            try (var resultado = statement.executeQuery("SELECT count(*) FROM upgrade_procesos.usuarios")) {
                assertTrue(resultado.next());
                assertEquals(1, resultado.getInt(1));
            }
        }
    }

    @Test
    void migracionesEstanAplicadasSinCambiarUsuarios() {
        assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM usuarios WHERE id = ?", Integer.class, usuario));
    }
    @Test
    void migraV2ConProcesosCreadosYHoldsRegistradosSinPerderDatos() throws Exception {
        var config = org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("upgrade_v2").defaultSchema("upgrade_v2");
        config.target("2").load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword()); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO upgrade_v2.usuarios(login,password_hash,first_name,last_name,email)
                    VALUES ('previo', 'hash-prueba', 'Nombre', 'Apellido', 'previo@test.invalid')
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v2.procesos_reserva(usuario_id,profesional_id,fecha,hora,estado,creado_en,actualizado_en)
                    SELECT id,15,DATE '2026-12-01',TIME '09:30','CREADO',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    FROM upgrade_v2.usuarios
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v2.procesos_reserva(usuario_id,profesional_id,fecha,hora,estado,
                        hold_id,proceso_externo_id,vencimiento_hold,creado_en,actualizado_en)
                    SELECT id,15,DATE '2026-12-01',TIME '10:00','HOLD_REGISTRADO','hold-previo','externo-previo',
                        TIMESTAMPTZ '2026-12-01T15:00:00Z',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM upgrade_v2.usuarios
                    """);
            org.flywaydb.core.Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas("upgrade_v2").defaultSchema("upgrade_v2").load().migrate();
            try (var result = statement.executeQuery("SELECT estado,hold_id FROM upgrade_v2.procesos_reserva ORDER BY id")) {
                assertTrue(result.next());
                assertEquals("CREADO", result.getString(1));
                assertNull(result.getString(2));
                assertTrue(result.next());
                assertEquals("HOLD_REGISTRADO", result.getString(1));
                assertEquals("hold-previo", result.getString(2));
                assertFalse(result.next());
            }
        }
    }
}
