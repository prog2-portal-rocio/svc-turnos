package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.application.service.*;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;
import static com.example.svcturnos.reserva.support.ResultadosReservaFixtures.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;

@SpringBootTest(properties={"turnos.kafka.enabled=true", "turnos.kafka.integration-group-id=prueba-integracion",
        "turnos.kafka.actions-topic=alumnos.turnos.acciones.prueba-integracion", "spring.kafka.consumer.group-id=resultados-reserva-test",
        "turnos.kafka.phone-topic=catedra.turnos.telefono.prueba-integracion", "turnos.kafka.phone-publication-enabled=true",
        "turnos.kafka.results-initial-delay=3600000", "turnos.kafka.outbox-initial-delay=3600000",
        "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN"})
@ContextConfiguration(initializers=JwtTestContextInitializer.class)
@Import(ResultadosReservaIntegrationTest.Config.class)
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
class ResultadosReservaIntegrationTest {
    static final String ACCIONES="alumnos.turnos.acciones.prueba-integracion", GROUP="resultados-reserva-test";
    @Container @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18");
    @Container static KafkaContainer kafka=new KafkaContainer("apache/kafka:4.1.0");
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry r) { r.add("spring.kafka.bootstrap-servers",kafka::getBootstrapServers); }
    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean NewTopic acciones() { return TopicBuilder.name(ACCIONES).partitions(1).replicas(1).build(); }
        @Bean NewTopic telefono() { return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build(); }
        @Bean @Primary Reloj relojPrueba() { return new Reloj(); }
    }
    static class Reloj extends Clock {
        final AtomicReference<Instant> ahora=new AtomicReference<>(AHORA);
        @Override public Instant instant() { return ahora.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired InboxReservasPort inbox;
    @Autowired OutboxInformacionPort outbox;
    @Autowired AplicarResultadoReservaPort aplicar;
    @Autowired RecibirEventoReservaUseCase recibir;
    @Autowired RecuperarResultadosReservaService recuperar;
    @Autowired EventoReservaDecoder decoder;
    @Autowired PublicarInformacionService publicador;
    @Autowired KafkaTemplate<byte[],byte[]> kafkaTemplate;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired Reloj reloj;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    final JsonMapper json=JsonMapper.builder().build();
    static final AtomicLong OFFSET=new AtomicLong(10000);
    ProcesoReserva local;
    long reservaExterna;
    EventoInbox pedido;
    Usuario usuario;

    @BeforeEach void preparar() {
        ContainerTestUtils.waitForAssignment(registry.getListenerContainer("reserva-acciones"),1);
        reloj.ahora.set(AHORA);
        String login=UUID.randomUUID().toString();
        Long owner=jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?,'hash-prueba','Paciente','Prueba',?) RETURNING id
                """,Long.class,login,login+"@test.invalid");
        usuario=new Usuario(owner,login,"hash-prueba","Paciente","Prueba",login+"@test.invalid",null,"es",true);
        local=crearProceso(login,owner);
        reservaExterna=10000+local.id();
        pedido=crearPedido(UUID.randomUUID().toString(),local);
    }
    @AfterEach void cerrar() {
        outbox.depurarVencidos(AHORA.plus(Duration.ofDays(2)));
        var listener=registry.getListenerContainer("reserva-acciones");
        if (!listener.isRunning()) { listener.start();ContainerTestUtils.waitForAssignment(listener,1); }
    }
    ProcesoReserva crearProceso(String externo,Long owner) {
        return procesos.guardar(new ProcesoReserva(null,owner,15L,LocalDate.of(2026,12,1),LocalTime.of(9,30),
                EstadoProcesoReserva.ESPERANDO_TELEFONO,"hold-"+externo,externo,AHORA.plusSeconds(300),AHORA,AHORA,null));
    }
    EventoInbox crearPedido(String eventId,ProcesoReserva p) {
        String contenido=solicitud(eventId,p.procesoExternoId(),p.holdId()).replace("2026-09-10T14:44:50Z",AHORA.plusSeconds(200).toString());
        return recibir.recibir(decoder.decodificar(mensaje(contenido,p.procesoExternoId(),OFFSET.incrementAndGet())));
    }
    ObjectNode evento(String tipo) {
        var body=resultado(tipo,UUID.randomUUID().toString(),local.procesoExternoId(),local.holdId(),pedido.evento().eventId(),reloj.instant());
        if (body.has("reservationId")) { body.put("reservationId",reservaExterna); }
        return body;
    }
    EventoInbox recibirDirecto(ObjectNode body) {
        return recibir.recibir(decoder.decodificar(mensaje(body.toString(),body.path("reservationProcessId").asString(),OFFSET.incrementAndGet())));
    }
    long publicar(ObjectNode body) throws Exception {
        return kafkaTemplate.send(ACCIONES,body.path("reservationProcessId").asString().getBytes(StandardCharsets.UTF_8),json.writeValueAsBytes(body))
                .get(10,TimeUnit.SECONDS).getRecordMetadata().offset();
    }
    void esperarEstado(ObjectNode body,String estado) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(List.of(estado),jdbc.queryForList(
                "SELECT estado FROM eventos_reserva_inbox WHERE canonico AND event_id=?",String.class,body.path("eventId").asString())));
    }
    long offsetConfirmado() {
        try (var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers()))) {
            var offsets=admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
            var valor=offsets.get(new TopicPartition(ACCIONES,0));return valor==null ? -1 : valor.offset();
        } catch (Exception error) { throw new AssertionError("No se pudo leer offset del broker aislado",error); }
    }
    void esperarCommit(long offset) { await().atMost(Duration.ofSeconds(20)).until(() -> offsetConfirmado()>offset); }
    EnvioInformacion responder() {
        return outbox.registrar(local.id(),usuario.id(),pedido.id(),new Telefono(NUMERO),new DestinoInformacion(GRUPO,TOPIC));
    }
    void recuperarTodo() {
        long cursor=0;
        for (int i=0;i<10;i++) { cursor=recuperar.recuperar(cursor,100);if (cursor==0) { break; } }
    }

    @ParameterizedTest @CsvSource({"AppointmentConfirmed,CONFIRMADA", "AdditionalInformationRejected,ESPERANDO_TELEFONO",
            "AppointmentProcessExpired,PROCESO_EXPIRADO", "AppointmentProcessInvalid,PROCESO_INVALIDO", "AppointmentCancelled,CANCELADA"})
    void cincoEventosRealesAplicanYConfirmanOffsetDespuesDePersistir(String tipo,EstadoProcesoReserva destino) throws Exception {
        if (tipo.equals(RECHAZO)) { responder();publicador.publicarPendientes(1,5,Duration.ofSeconds(60),Duration.ofSeconds(10)); }
        reloj.ahora.set(AHORA.plusSeconds(1));var body=evento(tipo);long offset=publicar(body);
        esperarEstado(body,"RESULTADO_APLICADO");esperarCommit(offset);
        var actualizado=procesos.buscarPorId(local.id()).orElseThrow();
        assertEquals(destino,actualizado.estado());assertEquals(usuario.id(),actualizado.usuarioId());
        assertEquals(destino==EstadoProcesoReserva.CONFIRMADA || destino==EstadoProcesoReserva.CANCELADA ? reservaExterna : null,actualizado.reservaId());
        assertEquals(1L,actualizado.version());
    }
    @Test void duplicadoTrasReiniciarConsumidorNoAplicaDosVeces() throws Exception {
        var body=evento(CONFIRMACION);long offset=publicar(body);esperarEstado(body,"RESULTADO_APLICADO");esperarCommit(offset);
        var primero=procesos.buscarPorId(local.id()).orElseThrow();
        var listener=registry.getListenerContainer("reserva-acciones");listener.stop();listener.start();ContainerTestUtils.waitForAssignment(listener,1);
        long duplicado=publicar(body);esperarCommit(duplicado);
        assertEquals(primero,procesos.buscarPorId(local.id()).orElseThrow());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM eventos_reserva_inbox WHERE event_id=? AND estado='DUPLICADO'",Integer.class,body.path("eventId").asString()));
    }
    @Test void cancelacionAdelantadaNoRetrocedeAlLlegarConfirmacion() {
        var cancelacion=evento(CANCELACION);assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,recibirDirecto(cancelacion).estado());
        var cerrado=procesos.buscarPorId(local.id()).orElseThrow();
        var confirmacion=evento(CONFIRMACION);confirmacion.put("occurredAt",AHORA.minusSeconds(1).toString());
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,recibirDirecto(confirmacion).estado());
        assertEquals(cerrado,procesos.buscarPorId(local.id()).orElseThrow());
    }
    @Test void confirmacionSeguidaDeCancelacionLegitimaConservaReservaId() {
        recibirDirecto(evento(CONFIRMACION));reloj.ahora.set(AHORA.plusSeconds(1));
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,recibirDirecto(evento(CANCELACION)).estado());
        var cancelado=procesos.buscarPorId(local.id()).orElseThrow();assertEquals(EstadoProcesoReserva.CANCELADA,cancelado.estado());assertEquals(reservaExterna,cancelado.reservaId());
    }
    @ParameterizedTest @ValueSource(strings={EXPIRACION,INVALIDEZ})
    void cierreNoSeReabrePorConfirmacionTardia(String cierre) {
        recibirDirecto(evento(cierre));reloj.ahora.set(AHORA.plusSeconds(2));
        assertEquals(EstadoEventoReserva.REQUIERE_RECONCILIACION,recibirDirecto(evento(CONFIRMACION)).estado());
        assertNotEquals(EstadoProcesoReserva.CONFIRMADA,procesos.buscarPorId(local.id()).orElseThrow().estado());
    }
    @ParameterizedTest @ValueSource(strings={"groupId","holdId","professionalId","date","startTime"})
    void identidadInconsistenteQuedaConservadaSinModificarProceso(String campo) {
        var body=evento(CONFIRMACION);
        switch (campo) {
            case "professionalId" -> body.put(campo,99);
            case "date" -> body.put(campo,"2026-12-02");
            case "startTime" -> body.put(campo,"10:30");
            default -> body.put(campo,"ajeno");
        }
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE,recibirDirecto(body).estado());
        assertEquals(local,procesos.buscarPorId(local.id()).orElseThrow());
    }
    @Test void resultadoAntesDeLaSolicitudSeRecuperaSinReentregaKafka() {
        String request=UUID.randomUUID().toString();var body=evento(CONFIRMACION);body.put("requestEventId",request);
        assertEquals(EstadoEventoReserva.PENDIENTE_CORRELACION,recibirDirecto(body).estado());
        crearPedido(request,local);recuperarTodo();
        assertEquals(EstadoProcesoReserva.CONFIRMADA,procesos.buscarPorId(local.id()).orElseThrow().estado());
    }
    @Test void resultadoAntesDelProcesoSeRecuperaDesdeInboxTrasReinicio() {
        String externo=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
        var body=resultado(CONFIRMACION,UUID.randomUUID().toString(),externo,"hold-"+externo,request,AHORA);
        var pendiente=recibirDirecto(body);assertEquals(EstadoEventoReserva.PENDIENTE_CORRELACION,pendiente.estado());
        var nuevo=crearProceso(externo,usuario.id());crearPedido(request,nuevo);
        var reiniciado=new RecuperarResultadosReservaService(inbox,new ProcesarResultadoReservaService(aplicar));
        reiniciado.recuperar(0,100);
        assertEquals(EstadoProcesoReserva.CONFIRMADA,procesos.buscarPorId(nuevo.id()).orElseThrow().estado());
    }
    @Test void resultadosHistoricosPendientesDeTipoSeInterpretanDesdeJsonOriginal() {
        var body=evento(CONFIRMACION);
        var guardado=inbox.conservar(decoder.decodificar(mensaje(body.toString(),local.procesoExternoId(),OFFSET.incrementAndGet())));
        jdbc.update("UPDATE eventos_reserva_inbox SET estado='PENDIENTE_TIPO' WHERE id=?",guardado.id());
        recuperarTodo();assertEquals(EstadoProcesoReserva.CONFIRMADA,procesos.buscarPorId(local.id()).orElseThrow().estado());
    }
    @Test void rechazoPermiteNuevoTelefonoConMismoRequestYNuevoEventoSinPerderHistoria() throws Exception {
        var previo=responder();publicador.publicarPendientes(1,5,Duration.ofSeconds(60),Duration.ofSeconds(10));
        reloj.ahora.set(AHORA.plusSeconds(1));var rechazo=evento(RECHAZO);recibirDirecto(rechazo);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE solicitud_id=? AND habilitada",Integer.class,pedido.id()));
        String token=new JwtTokenAdapter(encoder).generarToken(usuario);reloj.ahora.set(AHORA.plusSeconds(2));
        var barrera=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> peticion=() -> {
                barrera.await(10,TimeUnit.SECONDS);
                return mvc.perform(post("/api/reservas/holds/"+local.id()+"/telefono").header("Authorization","Bearer "+token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"123456789\"}"))
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            };
            var uno=executor.submit(peticion);var dos=executor.submit(peticion);
            String primero=json.readTree(uno.get(20,TimeUnit.SECONDS)).path("eventId").asString();
            assertEquals(primero,json.readTree(dos.get(20,TimeUnit.SECONDS)).path("eventId").asString());assertNotEquals(previo.eventId().toString(),primero);
        }
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE solicitud_id=?",Integer.class,pedido.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE solicitud_id=? AND habilitada",Integer.class,pedido.id()));
        assertEquals(List.of(pedido.evento().eventId()),jdbc.queryForList("SELECT DISTINCT request_event_id FROM informacion_adicional_outbox WHERE solicitud_id=?",String.class,pedido.id()));
        // Reentrega y otro rechazo viejo no deshabilitan la nueva respuesta.
        recibirDirecto(rechazo);var viejo=evento(RECHAZO);viejo.put("occurredAt",AHORA.toString());
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,recibirDirecto(viejo).estado());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE solicitud_id=? AND habilitada",Integer.class,pedido.id()));
    }
    @Test void resultadoFinalDetieneOutboxYAcusesTardiosNoLaResucitan() {
        var envio=responder();var intento=outbox.reclamar(AHORA,Duration.ofSeconds(60),5).orElseThrow();
        recibirDirecto(evento(CONFIRMACION));
        outbox.registrarResultado(envio.eventId(),intento.intentoId(),true,AHORA,Duration.ofSeconds(10));
        assertFalse(jdbc.queryForObject("SELECT habilitada FROM informacion_adicional_outbox WHERE event_id=?",Boolean.class,envio.eventId()));
        assertNull(jdbc.queryForObject("SELECT telefono FROM informacion_adicional_outbox WHERE event_id=?",String.class,envio.eventId()));
        assertTrue(outbox.reclamar(AHORA.plusSeconds(60),Duration.ofSeconds(60),5).isEmpty());
    }
    @Test void falloAlMarcarInboxRevierteProcesoYOutboxYNoConfirmaOffset() throws Exception {
        var envio=responder();var body=evento(CONFIRMACION);String eventId=body.path("eventId").asString();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fallo_resultado_inbox() RETURNS trigger AS $$
                BEGIN IF NEW.estado='RESULTADO_APLICADO' THEN RAISE EXCEPTION 'fallo simulado'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql
                """);
        String trigger="fallo_resultado_"+local.id();
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON eventos_reserva_inbox FOR EACH ROW WHEN (NEW.event_id='"+eventId+"') EXECUTE FUNCTION fallo_resultado_inbox()");
        long offset;
        try {
            offset=publicar(body);
            await().atMost(Duration.ofSeconds(20)).until(() -> !registry.getListenerContainer("reserva-acciones").isRunning());
            assertEquals(local,procesos.buscarPorId(local.id()).orElseThrow());
            assertEquals("RECIBIDO",jdbc.queryForObject("SELECT estado FROM eventos_reserva_inbox WHERE event_id=? AND canonico",String.class,eventId));
            assertTrue(jdbc.queryForObject("SELECT habilitada FROM informacion_adicional_outbox WHERE event_id=?",Boolean.class,envio.eventId()));
            assertNotNull(jdbc.queryForObject("SELECT telefono FROM informacion_adicional_outbox WHERE event_id=?",String.class,envio.eventId()));
            assertTrue(offsetConfirmado()<=offset);
        } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON eventos_reserva_inbox"); }
        recuperarTodo();assertEquals(EstadoProcesoReserva.CONFIRMADA,procesos.buscarPorId(local.id()).orElseThrow().estado());
        var listener=registry.getListenerContainer("reserva-acciones");listener.start();ContainerTestUtils.waitForAssignment(listener,1);esperarCommit(offset);
        assertEquals(1L,procesos.buscarPorId(local.id()).orElseThrow().version());
    }
    @Test void dosAplicadoresConcurrentesNoDuplicanLaTransicion() throws Exception {
        var body=evento(CONFIRMACION);
        var conservado=inbox.conservar(decoder.decodificar(mensaje(body.toString(),local.procesoExternoId(),OFFSET.incrementAndGet())));
        var barrera=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<EventoInbox> tarea=() -> { barrera.await(10,TimeUnit.SECONDS);return aplicar.aplicar(conservado.id()); };
            var uno=executor.submit(tarea);var dos=executor.submit(tarea);
            assertEquals(uno.get(20,TimeUnit.SECONDS),dos.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1L,procesos.buscarPorId(local.id()).orElseThrow().version());
    }
    @Test void rechazoPosteriorAConfirmacionNoHabilitaUnNuevoEnvio() {
        responder();recibirDirecto(evento(CONFIRMACION));reloj.ahora.set(AHORA.plusSeconds(1));
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,recibirDirecto(evento(RECHAZO)).estado());
        assertEquals(EstadoProcesoReserva.CONFIRMADA,procesos.buscarPorId(local.id()).orElseThrow().estado());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE proceso_id=? AND habilitada",Integer.class,local.id()));
    }
    @Test void unaReservaExternaNoSeAsignaAOtroProceso() {
        recibirDirecto(evento(CONFIRMACION));
        var otro=crearProceso(UUID.randomUUID().toString(),usuario.id());var solicitud=crearPedido(UUID.randomUUID().toString(),otro);
        var body=resultado(CONFIRMACION,UUID.randomUUID().toString(),otro.procesoExternoId(),otro.holdId(),solicitud.evento().eventId(),AHORA);
        body.put("reservationId",reservaExterna);
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE,recibirDirecto(body).estado());
        assertEquals(otro,procesos.buscarPorId(otro.id()).orElseThrow());
    }
    @Test void rechazoVencidoConservaHistoriaPeroNoAdmiteNuevoTelefono() throws Exception {
        responder();publicador.publicarPendientes(1,5,Duration.ofSeconds(60),Duration.ofSeconds(10));
        reloj.ahora.set(AHORA.plusSeconds(200));var rechazo=evento(RECHAZO);recibirDirecto(rechazo);
        String token=new JwtTokenAdapter(encoder).generarToken(usuario);
        mvc.perform(post("/api/reservas/holds/"+local.id()+"/telefono").header("Authorization","Bearer "+token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"123456789\"}"))
                .andExpect(status().isGone());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE proceso_id=?",Integer.class,local.id()));
    }

    @Test void actualizaV7ConUnEnvioExistenteSinModificarSuIdentidadNiSuProceso() throws Exception {
        var config=org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .schemas("upgrade_v7_resultados").defaultSchema("upgrade_v7_resultados");
        config.target("7").load().migrate();
        var eventoId=UUID.randomUUID();
        try (var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
             var statement=connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO upgrade_v7_resultados.usuarios(login,password_hash,first_name,last_name,email)
                    VALUES ('existente','hash-prueba','Paciente','Prueba','existente@test.invalid')
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v7_resultados.procesos_reserva(usuario_id,profesional_id,fecha,hora,estado,
                        hold_id,proceso_externo_id,vencimiento_hold,creado_en,actualizado_en)
                    SELECT id,15,DATE '2026-12-01',TIME '09:30','ESPERANDO_TELEFONO','hold-previo','externo-previo',
                        TIMESTAMPTZ '2026-12-01T15:00:00Z',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM upgrade_v7_resultados.usuarios
                    """);
            statement.executeUpdate("""
                    INSERT INTO upgrade_v7_resultados.eventos_reserva_inbox(topic,particion,offset_mensaje,event_id,event_id_hash,
                        event_type,proceso_externo_id,hold_id,occurred_at,schema_version,payload_json,canonico,estado,
                        required_field,mensaje,expires_at,proceso_local_id)
                    SELECT 'acciones',0,1,'request-previo',decode(repeat('00',32),'hex'),'AdditionalInformationRequested',
                        'externo-previo','hold-previo',CURRENT_TIMESTAMP,1,'{}',true,'SOLICITUD_REGISTRADA','PHONE_NUMBER',
                        'Ingrese telefono',TIMESTAMPTZ '2026-12-01T15:00:00Z',id FROM upgrade_v7_resultados.procesos_reserva
                    """);
            try (var insert=connection.prepareStatement("""
                    INSERT INTO upgrade_v7_resultados.informacion_adicional_outbox(event_id,proceso_id,solicitud_id,group_id,topic,
                        proceso_externo_id,hold_id,request_event_id,telefono,ocurrido_en,vence_en,estado,proximo_intento)
                    SELECT ?,proceso_local_id,id,'prueba-integracion','catedra.turnos.telefono.prueba-integracion',
                        'externo-previo','hold-previo','request-previo','1234567',CURRENT_TIMESTAMP,
                        TIMESTAMPTZ '2026-12-01T15:00:00Z','PENDIENTE',CURRENT_TIMESTAMP FROM upgrade_v7_resultados.eventos_reserva_inbox
                    """)) { insert.setObject(1,eventoId);insert.executeUpdate(); }
            config.target(org.flywaydb.core.api.MigrationVersion.LATEST).load().migrate();
            try (var row=statement.executeQuery("""
                    SELECT o.event_id,o.habilitada,o.request_event_id,p.estado,p.reserva_id
                    FROM upgrade_v7_resultados.informacion_adicional_outbox o
                    JOIN upgrade_v7_resultados.procesos_reserva p ON p.id=o.proceso_id
                    """)) {
                assertTrue(row.next());assertEquals(eventoId,row.getObject(1,UUID.class));assertTrue(row.getBoolean(2));
                assertEquals("request-previo",row.getString(3));assertEquals("ESPERANDO_TELEFONO",row.getString(4));assertNull(row.getObject(5));
                assertFalse(row.next());
            }
        }
    }

    @Test void requestEventIdDeOtroProcesoNoSeConfundeConUnaSolicitudAdelantada() {
        var otro=crearProceso(UUID.randomUUID().toString(),usuario.id());var otroPedido=crearPedido(UUID.randomUUID().toString(),otro);
        var body=evento(CONFIRMACION);body.put("requestEventId",otroPedido.evento().eventId());
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE,recibirDirecto(body).estado());
        assertEquals(local,procesos.buscarPorId(local.id()).orElseThrow());
    }
    @Test void postgresExigeFechaParaLosResultadosDefinitivos() {
        var id=local.id();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE procesos_reserva SET estado='PROCESO_EXPIRADO',resultado_ocurrido_en=NULL WHERE id=?",id));
    }

    @ParameterizedTest @ValueSource(longs={0,100,210})
    void rechazosPersistidosConNanosegundosConservanOriginalYOrdenCoherente(long diferencia) {
        responder();publicador.publicarPendientes(1,5,Duration.ofSeconds(60),Duration.ofSeconds(10));
        Instant original=AHORA.plusNanos(123456789);reloj.ahora.set(original);
        var body=evento(RECHAZO);var primero=recibirDirecto(body);
        var persistido=procesos.buscarPorId(local.id()).orElseThrow();
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,primero.estado());
        assertEquals(original.truncatedTo(java.time.temporal.ChronoUnit.MICROS),persistido.resultadoOcurridoEn());
        assertEquals(original,inbox.buscarPorId(primero.id()).orElseThrow().evento().occurredAt());
        assertEquals(original.toString(),jdbc.queryForObject(
                "SELECT payload_json::json->>'occurredAt' FROM eventos_reserva_inbox WHERE id=?",String.class,primero.id()));
        assertEquals(EstadoEventoReserva.DUPLICADO,recibirDirecto(body).estado());
        assertEquals(persistido,procesos.buscarPorId(local.id()).orElseThrow());

        reloj.ahora.set(original.plusNanos(10000));var nueva=responder();
        var empate=evento(RECHAZO);empate.put("occurredAt",original.plusNanos(diferencia).toString());
        assertEquals(EstadoEventoReserva.REQUIERE_RECONCILIACION,recibirDirecto(empate).estado());
        assertTrue(jdbc.queryForObject("SELECT habilitada FROM informacion_adicional_outbox WHERE event_id=?",Boolean.class,nueva.eventId()));
        var anterior=evento(RECHAZO);anterior.put("occurredAt",original.minusNanos(1000).toString());
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,recibirDirecto(anterior).estado());

        publicador.publicarPendientes(1,5,Duration.ofSeconds(60),Duration.ofSeconds(10));
        reloj.ahora.set(original.plusMillis(1));var posterior=evento(RECHAZO);
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,recibirDirecto(posterior).estado());
        assertEquals(reloj.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),procesos.buscarPorId(local.id()).orElseThrow().resultadoOcurridoEn());
    }

    @Test void confirmacionPersistidaConNanosProtegeElFinalYPermiteCancelacionEnElMismoMicrosegundo() {
        Instant original=AHORA.plusNanos(123456789);reloj.ahora.set(original);
        recibirDirecto(evento(CONFIRMACION));var confirmado=procesos.buscarPorId(local.id()).orElseThrow();
        reloj.ahora.set(original.plusSeconds(1));
        assertEquals(EstadoEventoReserva.RESULTADO_OBSOLETO,recibirDirecto(evento(RECHAZO)).estado());
        assertEquals(confirmado,procesos.buscarPorId(local.id()).orElseThrow());
        var cancelacion=evento(CANCELACION);cancelacion.put("occurredAt",original.minusNanos(100).toString());
        assertEquals(EstadoEventoReserva.RESULTADO_APLICADO,recibirDirecto(cancelacion).estado());
        assertEquals(EstadoProcesoReserva.CANCELADA,procesos.buscarPorId(local.id()).orElseThrow().estado());
    }

}
