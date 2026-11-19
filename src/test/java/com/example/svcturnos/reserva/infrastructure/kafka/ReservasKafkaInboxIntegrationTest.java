package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;

@SpringBootTest(properties = {"turnos.kafka.enabled=true", "turnos.kafka.actions-topic=acciones-reservas-test",
        "spring.kafka.consumer.group-id=reservas-inbox-test",
        "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.ByteArraySerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer"})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Testcontainers
@DirtiesContext
@Import(ReservasKafkaInboxIntegrationTest.TopicTest.class)
class ReservasKafkaInboxIntegrationTest {
    static final String TOPIC = "acciones-reservas-test";
    static final String GROUP = "reservas-inbox-test";
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TopicTest {
        @Bean NewTopic acciones() { return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build(); }
    }
    @Autowired KafkaTemplate<byte[], byte[]> template;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxReservasPort inbox;
    @Autowired RecibirEventoReservaUseCase recibir;
    @Autowired CorrelacionarSolicitudInformacionUseCase correlacionar;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired EventoReservaDecoder decoder;
    static final AtomicLong OFFSET_MANUAL = new AtomicLong(1000);

    MessageListenerContainer contenedor() { return Objects.requireNonNull(registry.getListenerContainer("reserva-acciones")); }
    @BeforeEach void asignacion() { ContainerTestUtils.waitForAssignment(contenedor(), 1); }
    String id() { return UUID.randomUUID().toString(); }

    long publicar(String body, String key) throws Exception {
        return template.send(TOPIC, key.getBytes(StandardCharsets.UTF_8), body == null ? null : body.getBytes(StandardCharsets.UTF_8))
                .get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
    }

    long original(String eventId) {
        return jdbc.queryForObject("SELECT id FROM eventos_reserva_inbox WHERE canonico AND event_id=?", Long.class, eventId);
    }

    void esperarEstado(long offset, String estado) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(List.of(estado),
                jdbc.queryForList("SELECT estado FROM eventos_reserva_inbox WHERE topic=? AND particion=0 AND offset_mensaje=?", String.class, TOPIC, offset)));
    }

    void esperarCommit(long offset) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertTrue(offsetConfirmado() >= offset + 1));
    }

    long offsetConfirmado() throws Exception {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            var committed = offsets.get(new TopicPartition(TOPIC, 0));
            return committed == null ? -1 : committed.offset();
        }
    }

    ProcesoReserva proceso(String externo, String hold) {
        String login = id();
        Long usuario = jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?,'hash-prueba','Nombre','Apellido',?) RETURNING id
                """, Long.class, login, login + "@test.invalid");
        return procesos.guardar(ProcesoReserva.crear(usuario, 15L, LocalDate.of(2026, 9, 14), LocalTime.of(9, 30))
                .registrarHold(hold, externo, Instant.parse("2026-09-10T14:44:50Z")));
    }

    RecepcionEventoReserva recepcion(String body, String externo) {
        // Topic separado para las invocaciones directas: cada posición sigue siendo única en la inbox.
        return decoder.decodificar(mensaje(body, externo, OFFSET_MANUAL.incrementAndGet()));
    }

    @Test void solicitudValidaQuedaDuraderaAntesDelCommitYDuplicadoTrasReinicioNoRepiteEfectos() throws Exception {
        String eventId = id(); String externo = id(); String hold = id();
        var antes = proceso(externo, hold);
        String body = solicitud(eventId, externo, hold);
        long offset = publicar(body, externo);
        esperarEstado(offset, "SOLICITUD_REGISTRADA");
        esperarCommit(offset);
        var guardado = inbox.buscarPorId(original(eventId)).orElseThrow();
        assertEquals(eventId, guardado.evento().eventId());
        assertEquals(antes.id(), guardado.procesoLocalId());
        assertEquals(Instant.parse("2026-09-10T14:44:50Z"), guardado.evento().solicitud().expiresAt());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), jdbc.queryForObject("SELECT contenido FROM eventos_reserva_inbox WHERE id=?", byte[].class, guardado.id()));
        contenedor().stop();
        contenedor().start();
        ContainerTestUtils.waitForAssignment(contenedor(), 1);
        long duplicado = publicar(body, externo);
        esperarEstado(duplicado, "DUPLICADO");
        esperarCommit(duplicado);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eventos_reserva_inbox WHERE event_id=? AND canonico", Integer.class, eventId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM eventos_reserva_inbox WHERE event_id=?", Integer.class, eventId));
        assertEquals(antes, procesos.buscarPorId(antes.id()).orElseThrow());
    }

    @Test void eventoAdelantadoPuedeCorrelacionarseDespuesSinConfirmarLaReserva() throws Exception {
        String eventId = id(); String externo = id(); String hold = id();
        long offset = publicar(solicitud(eventId, externo, hold), externo);
        esperarEstado(offset, "PENDIENTE_CORRELACION");
        esperarCommit(offset);
        long inboxId = original(eventId);
        assertTrue(inbox.buscarPendientes(1000).contains(inboxId));
        var local = proceso(externo, hold);
        var asociado = correlacionar.correlacionar(inboxId);
        assertEquals(EstadoEventoReserva.SOLICITUD_REGISTRADA, asociado.estado());
        assertEquals(local.id(), asociado.procesoLocalId());
        assertEquals(local, procesos.buscarPorId(local.id()).orElseThrow());
        assertEquals(EstadoProcesoReserva.HOLD_REGISTRADO, local.estado());
        // La actualización condicional impide que un intento de correlación obsoleto retroceda el resultado.
        assertEquals(asociado, inbox.registrarCorrelacion(inboxId, new CorrelacionEvento(EstadoEventoReserva.PENDIENTE_CORRELACION, null)));
    }

    @ParameterizedTest @ValueSource(strings = {"AppointmentConfirmed", "AdditionalInformationRejected", "AppointmentProcessExpired", "AppointmentProcessInvalid", "AppointmentCancelled", "EventoFuturo"})
    void topicCompartidoConservaOtrosTiposSinEjecutarSusReglas(String tipo) throws Exception {
        String eventId = id(); String externo = id();
        String body = solicitud(eventId, externo, id()).replace("AdditionalInformationRequested", tipo);
        long offset = publicar(body, externo);
        esperarEstado(offset, "PENDIENTE_TIPO");
        esperarCommit(offset);
        assertEquals(tipo, inbox.buscarPorId(original(eventId)).orElseThrow().evento().eventType());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), inbox.buscarMensajePorId(original(eventId)).orElseThrow().contenido());
        assertTrue(inbox.buscarMensajePorId(Long.MAX_VALUE).isEmpty());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), jdbc.queryForObject("SELECT contenido FROM eventos_reserva_inbox WHERE event_id=? AND canonico", byte[].class, eventId));
    }

    @ParameterizedTest @ValueSource(strings = {"no-json", "null", "{}"})
    void mensajesInvalidosSeConservanYNoBloqueanElTopic(String body) throws Exception {
        long offset = publicar(body, id());
        esperarEstado(offset, "INVALIDO");
        esperarCommit(offset);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), jdbc.queryForObject(
                "SELECT contenido FROM eventos_reserva_inbox WHERE topic=? AND offset_mensaje=?", byte[].class, TOPIC, offset));
    }

    @Test void versionInvalidaYTombstoneNoSePierden() throws Exception {
        String externo = id();
        long offset = publicar(solicitud(id(), externo, id()).replace("\"schemaVersion\":1", "\"schemaVersion\":2"), externo);
        esperarEstado(offset, "INVALIDO");
        esperarCommit(offset);
        long tombstone = publicar(null, externo);
        esperarEstado(tombstone, "INVALIDO");
        esperarCommit(tombstone);
        assertNull(jdbc.queryForObject("SELECT contenido FROM eventos_reserva_inbox WHERE topic=? AND offset_mensaje=?", byte[].class, TOPIC, tombstone));
    }

    @Test void mismoIdConContenidoDistintoSeConservaComoConflictoSinSobrescribirOriginal() {
        String evento = id(); String externo = id();
        String body = solicitud(evento, externo, id());
        var primero = recibir.recibir(recepcion(body, externo));
        var conflicto = recibir.recibir(recepcion(body.replace("Ingrese un numero", "Otro mensaje con numero"), externo));
        assertEquals(EstadoEventoReserva.EVENT_ID_EN_CONFLICTO, conflicto.estado());
        assertEquals(primero.id(), conflicto.originalId());
        assertEquals(primero, inbox.buscarPorId(primero.id()).orElseThrow());
    }

    @Test void holdIncoherenteQuedaParaRevisionSinAsociarUnUsuarioIncorrecto() {
        String externo = id();
        var local = proceso(externo, "hold-correcto");
        var recibido = recibir.recibir(recepcion(solicitud(id(), externo, "otro-hold"), externo));
        assertEquals(EstadoEventoReserva.CORRELACION_INCONSISTENTE, recibido.estado());
        assertNull(recibido.procesoLocalId());
        assertEquals(local, procesos.buscarPorId(local.id()).orElseThrow());
    }

    @Test void recepcionesConcurrentesTienenUnSoloOriginalPorEventId() throws Exception {
        String externo = id(); String body = solicitud(id(), externo, id());
        var a = recepcion(body, externo); var b = recepcion(body, externo);
        var barrera = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primera = executor.submit(() -> { barrera.await(10, TimeUnit.SECONDS); return recibir.recibir(a); });
            var segunda = executor.submit(() -> { barrera.await(10, TimeUnit.SECONDS); return recibir.recibir(b); });
            var estados = List.of(primera.get(20, TimeUnit.SECONDS).estado().name(), segunda.get(20, TimeUnit.SECONDS).estado().name());
            assertTrue(estados.contains("DUPLICADO"));
            assertTrue(estados.contains("PENDIENTE_CORRELACION"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void falloDeConservacionOCorrelacionNoConfirmaYSeRecuperaAlReentregar(boolean correlacion) throws Exception {
        String eventId = id(); String externo = id();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fallo_inbox_test() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'fallo simulado de inbox'; END; $$ LANGUAGE plpgsql
                """);
        String operacion = correlacion ? "UPDATE" : "INSERT";
        jdbc.execute("CREATE TRIGGER fallo_inbox_test BEFORE " + operacion + " ON eventos_reserva_inbox "
                + "FOR EACH ROW WHEN (NEW.event_id = '" + eventId + "') EXECUTE FUNCTION fallo_inbox_test()");
        long offset;
        try {
            offset = publicar(solicitud(eventId, externo, id()), externo);
            await().atMost(Duration.ofSeconds(20)).until(() -> !contenedor().isRunning());
            assertTrue(offsetConfirmado() <= offset);
            assertEquals(correlacion ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM eventos_reserva_inbox WHERE event_id=?", Integer.class, eventId));
            if (correlacion) { assertEquals(EstadoEventoReserva.RECIBIDO, inbox.buscarPorId(original(eventId)).orElseThrow().estado()); }
        } finally {
            jdbc.execute("DROP TRIGGER fallo_inbox_test ON eventos_reserva_inbox");
        }
        contenedor().start();
        ContainerTestUtils.waitForAssignment(contenedor(), 1);
        esperarEstado(offset, "PENDIENTE_CORRELACION");
        esperarCommit(offset);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM eventos_reserva_inbox WHERE event_id=?", Integer.class, eventId));
    }
    @Test void identificadoresOpacosLargosYCamposDesconocidosNoImponenLimitesInventados() {
        String eventId = id() + "x".repeat(4000);
        String externo = id() + "p".repeat(4000);
        String body = solicitud(eventId, externo, id()).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"desconocido\":\"\\u0000\"");
        var primero = recibir.recibir(recepcion(body, externo));
        assertEquals(EstadoEventoReserva.PENDIENTE_CORRELACION, primero.estado());
        var duplicado = recibir.recibir(recepcion(body, externo));
        assertEquals(EstadoEventoReserva.DUPLICADO, duplicado.estado());
        assertEquals(primero.id(), duplicado.originalId());
    }

    @Test void mismoJsonConOtroOrdenDeCamposSigueSiendoDuplicado() {
        String externo = id(); String eventId = id();
        String body = solicitud(eventId, externo, id());
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var nodo = (tools.jackson.databind.node.ObjectNode) json.readTree(body);
        var eventType = nodo.remove("eventType");
        nodo.set("eventType", eventType);
        var primero = recibir.recibir(recepcion(body, externo));
        var duplicado = recibir.recibir(recepcion(json.writeValueAsString(nodo), externo));
        assertEquals(EstadoEventoReserva.DUPLICADO, duplicado.estado());
        assertEquals(primero.id(), duplicado.originalId());
    }
}
