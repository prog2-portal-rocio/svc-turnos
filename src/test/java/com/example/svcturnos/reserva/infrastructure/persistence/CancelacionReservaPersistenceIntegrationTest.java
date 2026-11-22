package com.example.svcturnos.reserva.infrastructure.persistence;

import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ContextConfiguration(initializers=JwtTestContextInitializer.class)
@Testcontainers
class CancelacionReservaPersistenceIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Autowired CancelacionReservaPersistencePort store;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.example.svcturnos.reserva.domain.ports.in.CancelarReservaUseCase cancelar;
    @org.springframework.test.context.bean.override.mockito.MockitoBean UsuarioAutenticadoPort autenticado;
    @org.springframework.test.context.bean.override.mockito.MockitoBean ConsultarReservasRemotasPort consultas;
    @org.springframework.test.context.bean.override.mockito.MockitoBean CancelarReservaRemotaPort remoto;
    static final Instant AHORA=Instant.parse("2026-11-21T10:00:00Z");
    Long usuario;
    String externo;
    Long reserva;
    @BeforeEach void preparar() {
        String login=UUID.randomUUID().toString(); externo=UUID.randomUUID().toString();
        usuario=jdbc.queryForObject("INSERT INTO usuarios(login,password_hash,first_name,last_name,email) VALUES (?,'hash-prueba','Nombre','Apellido',?) RETURNING id",
                Long.class,login,login+"@test.invalid"); reserva=usuario+100000;
        org.mockito.Mockito.when(autenticado.obtenerUsuarioId()).thenReturn(usuario);
    }
    ReservaCentral central(EstadoReservaCentral estado) {
        return new ReservaCentral(reserva,externo,PacienteReserva.identificadorExterno(usuario),LocalDate.of(2026,12,1),
                LocalTime.of(9,30),LocalTime.of(10,0),15L,"Ana","Gomez",estado,AHORA,AHORA,
                estado==EstadoReservaCentral.CANCELLED ? AHORA.plusSeconds(1) : null);
    }
    ProcesoReserva local(EstadoProcesoReserva estado) {
        return procesos.guardar(new ProcesoReserva(null,usuario,15L,LocalDate.of(2026,12,1),LocalTime.of(9,30),estado,
                "h-"+externo,externo,AHORA.plusSeconds(600),AHORA,AHORA,null,
                estado==EstadoProcesoReserva.CONFIRMADA ? reserva : null,AHORA));
    }
    @Test void reservaCentralSinProcesoConservaIntentoYResultadoSinInventarProceso() {
        var datos=central(EstadoReservaCentral.CONFIRMED);
        assertTrue(store.preparar(usuario,datos).enviar());
        assertFalse(store.preparar(usuario,datos).enviar());
        store.registrarFallo(usuario,externo,EstadoCancelacion.INCIERTA);
        assertFalse(store.preparar(usuario,datos).enviar());
        var resultado=store.confirmar(usuario,new CancelacionConfirmada(reserva,externo,AHORA.plusSeconds(1)));
        assertEquals(EstadoCancelacion.CANCELADA,resultado.estado());
        assertFalse(resultado.requiereReconciliacion()); assertTrue(procesos.buscarPorProcesoExternoId(externo).isEmpty());
        assertFalse(store.preparar(usuario,datos).enviar());
    }
    @Test void rechazoConocidoPermiteNuevoIntentoExplicito() {
        var datos=central(EstadoReservaCentral.CONFIRMED);
        store.preparar(usuario,datos); store.registrarFallo(usuario,externo,EstadoCancelacion.RECHAZADA);
        assertTrue(store.preparar(usuario,datos).enviar()); assertFalse(store.preparar(usuario,datos).enviar());
    }
    @Test void dosSolicitudesConcurrentesSoloUnaPuedeEnviar() throws Exception {
        var datos=central(EstadoReservaCentral.CONFIRMED);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var inicio=new CountDownLatch(1);
            Callable<Boolean> intento=() -> { inicio.await();return store.preparar(usuario,datos).enviar(); };
            var a=executor.submit(intento);var b=executor.submit(intento);inicio.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cancelaciones_reserva WHERE proceso_externo_id=?",Integer.class,externo));
    }
    @Test void aplicaCancelacionDefinitivaYSigueProtegidaContraEventosAntiguos() {
        var local=local(EstadoProcesoReserva.CONFIRMADA);
        store.preparar(usuario,central(EstadoReservaCentral.CONFIRMED));
        var resultado=store.confirmar(usuario,new CancelacionConfirmada(reserva,externo,AHORA.plusSeconds(1)));
        assertFalse(resultado.requiereReconciliacion());
        var actualizado=procesos.buscarPorId(local.id()).orElseThrow();
        assertEquals(EstadoProcesoReserva.CANCELADA,actualizado.estado()); assertEquals(reserva,actualizado.reservaId());
        assertEquals(local.vencimientoHold(),actualizado.vencimientoHold());
        assertEquals(actualizado,actualizado.registrarCancelacionRest(reserva,AHORA.minusSeconds(1),AHORA));
        assertEquals(resultado,store.registrarFallo(usuario,externo,EstadoCancelacion.RECHAZADA));
    }
    @Test void confirmacionKafkaPuedeResolverIntentoInciertoSinReenviar() {
        var local=local(EstadoProcesoReserva.CONFIRMADA);
        store.preparar(usuario,central(EstadoReservaCentral.CONFIRMED));store.registrarFallo(usuario,externo,EstadoCancelacion.INCIERTA);
        var cancelado=procesos.guardar(local.registrarCancelacionRest(reserva,AHORA.plusSeconds(1),AHORA.plusSeconds(2)));
        store.confirmarDesdeEvento(cancelado,reserva,AHORA.plusSeconds(1));
        assertEquals(EstadoCancelacion.CANCELADA,store.buscar(externo).orElseThrow().estado());
        assertFalse(store.preparar(usuario,central(EstadoReservaCentral.CONFIRMED)).enviar());
    }
    @Test void evidenciaAnteriorNoSobrescribeEstadoValidoYQuedaParaReconciliar() {
        var local=local(EstadoProcesoReserva.CONFIRMADA);
        store.preparar(usuario,central(EstadoReservaCentral.CONFIRMED));
        var resultado=store.confirmar(usuario,new CancelacionConfirmada(reserva,externo,AHORA.minusSeconds(1)));
        assertEquals(EstadoCancelacion.CANCELADA,resultado.estado());assertTrue(resultado.requiereReconciliacion());
        assertEquals(local,procesos.buscarPorId(local.id()).orElseThrow());
    }
    @Test void centralCanceladaConservaResultadoSinPost() {
        var resultado=store.preparar(usuario,central(EstadoReservaCentral.CANCELLED));
        assertFalse(resultado.enviar()); assertEquals(EstadoCancelacion.CANCELADA,resultado.cancelacion().estado());
    }
    @Test void propietarioContradictorioNuncaReemplazaIntento() {
        var datos=central(EstadoReservaCentral.CONFIRMED);store.preparar(usuario,datos);
        Long otro=usuario+1000;
        assertThrows(CancelarReservaException.class,()->store.preparar(otro,datos));
        assertEquals(usuario,store.buscar(externo).orElseThrow().usuarioId());
    }
    @Test void falloDurantePersistenciaRevierteResultadoYConservaIntencion() {
        var local=local(EstadoProcesoReserva.CONFIRMADA);store.preparar(usuario,central(EstadoReservaCentral.CONFIRMED));
        String esquema="fallo_"+usuario;
        jdbc.execute("CREATE FUNCTION "+esquema+"() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fallo simulado''; END'");
        jdbc.execute("CREATE TRIGGER "+esquema+" BEFORE UPDATE ON procesos_reserva FOR EACH ROW WHEN (OLD.id="+local.id()+") EXECUTE FUNCTION "+esquema+"()");
        try {
            var evidencia=new CancelacionConfirmada(reserva,externo,AHORA.plusSeconds(1));
            assertThrows(org.springframework.dao.DataAccessException.class,()->store.confirmar(usuario,evidencia));
            assertEquals(EstadoCancelacion.SOLICITADA,store.buscar(externo).orElseThrow().estado());
            assertEquals(local,procesos.buscarPorId(local.id()).orElseThrow());
        } finally { jdbc.execute("DROP TRIGGER "+esquema+" ON procesos_reserva");jdbc.execute("DROP FUNCTION "+esquema+"()"); }
    }
    @Test void migraDesdeV8SinModificarUsuariosNiProcesos() throws Exception {
        var config=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("upgrade_v8_cancelacion").defaultSchema("upgrade_v8_cancelacion");
        config.target("8").load().migrate();
        try(var conexion=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var statement=conexion.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO upgrade_v8_cancelacion.usuarios(login,password_hash,first_name,last_name,email)
                    VALUES ('previo','hash-prueba','Nombre','Apellido','previo@test.invalid')
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v8_cancelacion.procesos_reserva(usuario_id,profesional_id,fecha,hora,estado,
                        hold_id,proceso_externo_id,vencimiento_hold,creado_en,actualizado_en,reserva_id,resultado_ocurrido_en)
                    SELECT id,15,DATE '2026-12-01',TIME '09:30','CONFIRMADA','hold-previo','externo-previo',
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,8251,CURRENT_TIMESTAMP
                    FROM upgrade_v8_cancelacion.usuarios
                    """);
            config.target(org.flywaydb.core.api.MigrationVersion.LATEST).load().migrate();
            try(var filas=statement.executeQuery("SELECT estado,reserva_id FROM upgrade_v8_cancelacion.procesos_reserva")) {
                assertTrue(filas.next());assertEquals("CONFIRMADA",filas.getString(1));assertEquals(8251L,filas.getLong(2));
                assertFalse(filas.next());
            }
            try(var filas=statement.executeQuery("SELECT count(*) FROM upgrade_v8_cancelacion.cancelaciones_reserva")) {
                assertTrue(filas.next());assertEquals(0,filas.getLong(1));
            }
        }
    }

    @Test void dosPeticionesConcurrentesEjecutanUnSoloPostSinTransaccionDuranteHttp() throws Exception {
        var datos=central(EstadoReservaCentral.CONFIRMED);
        org.mockito.Mockito.when(consultas.consultar(org.mockito.ArgumentMatchers.eq(datos.externalPatientId()),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaginaReservas(List.of(datos),1,0,100));
        var entroPost=new CountDownLatch(1);var terminarPost=new CountDownLatch(1);
        org.mockito.Mockito.when(remoto.cancelar(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(reserva)))
                .thenAnswer(call->{
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    entroPost.countDown();assertTrue(terminarPost.await(10,TimeUnit.SECONDS));
                    return new CancelacionConfirmada(reserva,externo,AHORA.plusSeconds(1));
                });
        var solicitud=new SolicitudCancelacion(externo,null);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var primera=executor.submit(()->cancelar.cancelar(solicitud));
            try {
                assertTrue(entroPost.await(10,TimeUnit.SECONDS));
                var segunda=executor.submit(()->cancelar.cancelar(solicitud));
                assertEquals(EstadoCancelacion.SOLICITADA,segunda.get(5,TimeUnit.SECONDS).estado());
            } finally { terminarPost.countDown(); }
            assertEquals(EstadoCancelacion.CANCELADA,primera.get(10,TimeUnit.SECONDS).estado());
        }
        org.mockito.Mockito.verify(remoto,org.mockito.Mockito.times(1)).cancelar(solicitud,reserva);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"usuario","profesional","fecha","hora","reserva"})
    void asociacionLocalContradictoriaNuncaAutorizaCancelacion(String campo) {
        String login=UUID.randomUUID().toString();
        Long ajeno=jdbc.queryForObject("INSERT INTO usuarios(login,password_hash,first_name,last_name,email) VALUES (?,'hash-prueba','Nombre','Apellido',?) RETURNING id",
                Long.class,login,login+"@test.invalid");
        var datos=central(EstadoReservaCentral.CONFIRMED);
        procesos.guardar(new ProcesoReserva(null,campo.equals("usuario") ? ajeno : usuario,
                campo.equals("profesional") ? 99L : datos.professionalId(),
                campo.equals("fecha") ? datos.appointmentDate().plusDays(1) : datos.appointmentDate(),
                campo.equals("hora") ? datos.startTime().plusMinutes(30) : datos.startTime(),
                EstadoProcesoReserva.CONFIRMADA,"hold-"+externo,externo,AHORA.plusSeconds(600),AHORA,AHORA,null,
                campo.equals("reserva") ? reserva+10000000 : reserva,AHORA));
        org.mockito.Mockito.when(consultas.consultar(org.mockito.ArgumentMatchers.eq(datos.externalPatientId()),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaginaReservas(List.of(datos),1,0,100));
        var solicitud=new SolicitudCancelacion(externo,null);
        assertThrows(CancelarReservaException.class,()->cancelar.cancelar(solicitud));
        assertThrows(CancelarReservaException.class,()->store.preparar(usuario,datos));
        assertTrue(store.buscar(externo).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(remoto);
    }

}
