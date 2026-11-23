package com.example.svcturnos.reserva.infrastructure.integration;

import com.example.svcturnos.reserva.application.service.RecuperarResultadosReservaService;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ProcesarResultadoReservaUseCase;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.reserva.infrastructure.kafka.*;
import com.example.svcturnos.reserva.support.IntegracionRestSimulada;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;
import static com.example.svcturnos.reserva.support.ResultadosReservaFixtures.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Turnos completo: sin MockitoBean, usuarios/procesos preparados por SQL ni puertos externos sustituidos. */
@SpringBootTest(properties = {"turnos.kafka.enabled=true", "turnos.kafka.integration-group-id=prueba-integracion",
        "turnos.kafka.actions-topic=alumnos.turnos.acciones.prueba-integracion", "spring.kafka.consumer.group-id=flujo-reservas-test",
        "turnos.kafka.phone-topic=catedra.turnos.telefono.prueba-integracion", "turnos.kafka.phone-publication-enabled=true",
        "turnos.kafka.results-initial-delay=3600000", "turnos.kafka.outbox-initial-delay=3600000",
        "logging.level.org.apache.kafka=WARN", "logging.level.org.springframework.kafka=WARN"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Import(FlujoReservasIntegrationTest.Config.class)
@Testcontainers
@DirtiesContext
class FlujoReservasIntegrationTest {
    static final String ACCIONES = "alumnos.turnos.acciones.prueba-integracion";
    static final String GRUPO_CONSUMIDOR = "flujo-reservas-test";
    static final String CLAVE_FICTICIA = "Clave-local-ficticia-43";
    static final IntegracionRestSimulada externa = new IntegracionRestSimulada();
    @Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");
    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry registry) throws IOException {
        externa.iniciar();
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("turnos.catalogo.base-url", externa::baseUrl);
        registry.add("turnos.catedra.base-url", externa::baseUrl);
        registry.add("turnos.catedra.technical-token", () -> IntegracionRestSimulada.CREDENCIAL_FICTICIA);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean NewTopic accionesFlujo() { return TopicBuilder.name(ACCIONES).partitions(1).replicas(1).build(); }
        @Bean NewTopic telefonoFlujo() { return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build(); }
        @Bean @Primary Reloj relojFlujo() { return new Reloj(); }
    }

    static class Reloj extends Clock {
        final AtomicReference<Clock> delegado = new AtomicReference<>(Clock.systemUTC());
        @Override public Instant instant() { return delegado.get().instant(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return delegado.get().withZone(zone); }
    }

    record Sesion(long usuarioId, String jwt) {
        @Override public String toString() { return "Sesion[usuarioId=" + usuarioId + "]"; }
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired InboxReservasPort inbox;
    @Autowired ProcesarResultadoReservaUseCase procesar;
    @Autowired EventoReservaDecoder decoder;
    @Autowired OutboxInformacionScheduler publicador;
    @Autowired KafkaTemplate<byte[], byte[]> productor;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired Reloj reloj;
    IntegracionRestSimulada.Caso caso;

    @BeforeEach void preparar() {
        reloj.delegado.set(Clock.systemUTC());
        ContainerTestUtils.waitForAssignment(listeners.getListenerContainer("reserva-acciones"), 1);
    }

    @AfterEach void verificar() {
        var listener = listeners.getListenerContainer("reserva-acciones");
        if (!listener.isRunning()) { listener.start(); ContainerTestUtils.waitForAssignment(listener, 1); }
        externa.verificar();
    }

    @AfterAll static void cerrarServidor() { externa.close(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void reservaCompletaConConsultasCancelacionYAislamiento(boolean perderRespuesta202) throws Exception {
        mvc.perform(get("/api/disponibilidad").param("profesionalId", "15").param("fecha", "2026-12-01"))
                .andExpect(status().isUnauthorized());
        var propietario = registrarYAutenticar();
        var otro = registrarYAutenticar();
        caso = externa.preparar(propietario.usuarioId(), propietario.jwt(), perderRespuesta202, reloj);
        long procesoId = crearHoldDesdeHttp(propietario);
        comprobarAislamiento(otro, procesoId);
        confirmarDesdeHttp(propietario, procesoId, perderRespuesta202);
        if (perderRespuesta202) {
            // Una nueva petición puede recuperar progreso local, pero aún no hay evidencia para reenviar el POST.
            mvc.perform(autorizada(post(confirmacion(procesoId)), propietario)).andExpect(status().isConflict());
            var listener = listeners.getListenerContainer("reserva-acciones");
            listener.stop(); listener.start(); ContainerTestUtils.waitForAssignment(listener, 1);
        }
        var solicitud = solicitudTelefono();
        esperarCommit(publicar(solicitud).offset());
        esperarEstado(procesoId, EstadoProcesoReserva.ESPERANDO_TELEFONO);
        assertEquals(1, caso.confirmaciones.get());
        String eventId = enviarTelefonoDesdeHttp(propietario, procesoId);
        publicador.publicar();
        comprobarMensajeTelefono(eventId, solicitud.path("eventId").asString());
        assertEquals("ENVIADA", jdbc.queryForObject("SELECT estado FROM informacion_adicional_outbox WHERE event_id=?",
                String.class, UUID.fromString(eventId)));
        assertNull(jdbc.queryForObject("SELECT telefono FROM informacion_adicional_outbox WHERE event_id=?",
                String.class, UUID.fromString(eventId)));
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO, procesos.buscarPorId(procesoId).orElseThrow().estado());
        caso.confirmarReserva();
        var confirmacion = resultadoFinal(CONFIRMACION, solicitud.path("eventId").asString());
        confirmarPorKafkaORecuperarInbox(confirmacion, procesoId, perderRespuesta202);
        mvc.perform(autorizada(get("/api/procesos-reserva/" + procesoId), propietario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA")).andExpect(jsonPath("$.reservaId").value(caso.reserva));
        mvc.perform(autorizada(get("/api/reservas"), propietario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].reservationId").value(caso.reserva))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED")).andExpect(jsonPath("$.content[0].patientPhone").doesNotExist())
                .andExpect(jsonPath("$.content[0].patientFirstName").doesNotExist()).andExpect(jsonPath("$.content[0].patientLastName").doesNotExist());
        mvc.perform(autorizada(get("/api/reservas").param("externalPatientId", "user-" + propietario.usuarioId()), otro))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(autorizada(post("/api/reservas/" + caso.proceso + "/cancel"), otro)).andExpect(status().isNotFound());
        assertEquals(0, caso.cancelaciones.get());
        cancelarDesdeHttp(propietario);
        var cancelado = procesos.buscarPorId(procesoId).orElseThrow();
        assertEquals(EstadoProcesoReserva.CANCELADA, cancelado.estado());
        esperarCommit(publicar(resultadoFinal(CANCELACION, solicitud.path("eventId").asString())).offset());
        assertEquals(cancelado, procesos.buscarPorId(procesoId).orElseThrow());
        cancelarDesdeHttp(propietario);
        assertEquals(1, caso.cancelaciones.get());
        mvc.perform(autorizada(get("/api/reservas").param("status", "CANCELLED"), propietario))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
    }

    @Test void procesoCreadoDesdeHttpExpiraSinTelefonoYConservaLaPropiedad() throws Exception {
        var propietario = registrarYAutenticar();
        caso = externa.preparar(propietario.usuarioId(), propietario.jwt(), false, reloj);
        long procesoId = crearHoldDesdeHttp(propietario);
        confirmarDesdeHttp(propietario, procesoId, false);
        var solicitud = solicitudTelefono(); esperarCommit(publicar(solicitud).offset());
        esperarEstado(procesoId, EstadoProcesoReserva.ESPERANDO_TELEFONO);
        reloj.delegado.set(Clock.fixed(caso.vence.plusSeconds(1), ZoneOffset.UTC));
        caso.expirarProceso();
        esperarCommit(publicar(resultadoFinal(EXPIRACION, solicitud.path("eventId").asString())).offset());
        mvc.perform(autorizada(get("/api/procesos-reserva/" + procesoId), propietario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PROCESO_EXPIRADO")).andExpect(jsonPath("$.reservaId").isEmpty());
        mvc.perform(autorizada(post("/api/reservas/holds/" + procesoId + "/telefono"), propietario)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phoneNumber", NUMERO))))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE proceso_id=?", Integer.class, procesoId));
        assertEquals(1, caso.confirmaciones.get());
    }

    private Sesion registrarYAutenticar() throws Exception {
        String login = "paciente-" + UUID.randomUUID();
        var registro = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("login", login, "password", CLAVE_FICTICIA, "firstName", "  María  ", "lastName", "  Perez  ",
                        "email", login + "@test.invalid", "langKey", "es")))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstName").value("María")).andReturn().getResponse();
        long id = json.readTree(registro.getContentAsString()).path("id").asLong();
        var acceso = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", login, "password", CLAVE_FICTICIA))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = json.readTree(acceso.getContentAsString()).path("id_token").asString();
        var validado = jwtDecoder.decode(token);
        assertEquals("RS256", validado.getHeaders().get("alg"));
        assertEquals(Long.toString(id), validado.getSubject());
        assertTrue(validado.getAudience().containsAll(List.of("svc-turnos-api", "svc-catalogo-api")));
        assertEquals("catalogo:read", validado.getClaimAsString("scope"));
        return new Sesion(id, token);
    }

    private MockHttpServletRequestBuilder autorizada(MockHttpServletRequestBuilder peticion, Sesion sesion) {
        return peticion.header("Authorization", "Bearer " + sesion.jwt());
    }

    private long crearHoldDesdeHttp(Sesion propietario) throws Exception {
        mvc.perform(autorizada(get("/api/disponibilidad").param("profesionalId", "15").param("fecha", "2026-12-01"), propietario))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].inicio").value("09:30:00")).andExpect(jsonPath("$[0].fin").value("10:00:00"));
        var respuesta = mvc.perform(autorizada(post("/api/reservas/holds"), propietario).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"professionalId\":15,\"date\":\"2026-12-01\",\"startTime\":\"09:30\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("HOLD_REGISTRADO"))
                .andReturn().getResponse();
        long id = json.readTree(respuesta.getContentAsString()).path("procesoId").asLong();
        assertEquals(propietario.usuarioId(), procesos.buscarPorId(id).orElseThrow().usuarioId());
        assertEquals(caso.proceso, procesos.buscarPorId(id).orElseThrow().procesoExternoId());
        assertEquals(1, caso.holds.get());
        return id;
    }

    private void comprobarAislamiento(Sesion otro, long procesoId) throws Exception {
        mvc.perform(autorizada(get("/api/procesos-reserva/" + procesoId), otro)).andExpect(status().isNotFound());
        mvc.perform(autorizada(get("/api/procesos-reserva"), otro)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(autorizada(post(confirmacion(procesoId)), otro)).andExpect(status().isForbidden());
        mvc.perform(autorizada(post("/api/reservas/holds/" + procesoId + "/telefono"), otro)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phoneNumber", NUMERO))))
                .andExpect(status().isForbidden());
        assertEquals(0, caso.confirmaciones.get());
    }

    private String confirmacion(long id) { return "/api/reservas/holds/" + id + "/confirm"; }

    private void confirmarDesdeHttp(Sesion propietario, long id, boolean perdida) throws Exception {
        mvc.perform(autorizada(post(confirmacion(id)), propietario))
                .andExpect(status().is(perdida ? 503 : 202));
        assertEquals(perdida ? EstadoProcesoReserva.CONFIRMACION_INCIERTA : EstadoProcesoReserva.ESPERANDO_TELEFONO,
                procesos.buscarPorId(id).orElseThrow().estado());
        assertNull(procesos.buscarPorId(id).orElseThrow().reservaId());
    }

    private ObjectNode solicitudTelefono() {
        var body = (ObjectNode) json.readTree(com.example.svcturnos.reserva.support.EventosReservaFixtures
                .solicitud(UUID.randomUUID().toString(), caso.proceso, caso.hold));
        return body.put("expiresAt", caso.vence.toString()).put("occurredAt", reloj.instant().toString());
    }

    private ObjectNode resultadoFinal(String tipo, String requestId) {
        var body = resultado(tipo, UUID.randomUUID().toString(), caso.proceso, caso.hold, requestId, reloj.instant());
        if (tipo.equals(CONFIRMACION) || tipo.equals(CANCELACION)) { body.put("reservationId", caso.reserva); }
        return body;
    }

    private String enviarTelefonoDesdeHttp(Sesion propietario, long id) throws Exception {
        var respuesta = mvc.perform(autorizada(post("/api/reservas/holds/" + id + "/telefono"), propietario)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("phoneNumber", NUMERO))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.publicacion").value("PENDIENTE"))
                .andReturn().getResponse();
        return json.readTree(respuesta.getContentAsString()).path("eventId").asString();
    }

    private void comprobarMensajeTelefono(String eventId, String requestId) {
        var mensaje = leer(TOPIC, 0, 0, registro -> eventId.equals(json.readTree(registro.value()).path("eventId").asString()));
        var body = json.readTree(mensaje.value());
        assertArrayEquals(caso.proceso.getBytes(java.nio.charset.StandardCharsets.UTF_8), mensaje.key());
        assertEquals("AdditionalInformationSubmitted", body.path("eventType").asString());
        assertEquals(1, body.path("schemaVersion").asInt());
        assertEquals(GRUPO, body.path("groupId").asString());
        assertEquals(caso.proceso, body.path("reservationProcessId").asString());
        assertEquals(caso.hold, body.path("holdId").asString());
        assertEquals(requestId, body.path("requestEventId").asString());
        assertTrue(NUMERO.equals(body.path("information").path("phoneNumber").asString()), "Teléfono ficticio incorrecto");
    }

    private void confirmarPorKafkaORecuperarInbox(ObjectNode evento, long id, boolean interrumpir) throws Exception {
        var listener = listeners.getListenerContainer("reserva-acciones");
        if (interrumpir) { listener.stop(); }
        var metadata = publicar(evento);
        if (interrumpir) {
            // Corte controlado entre persistir la recepción real y aplicar el resultado, sin fabricar offsets.
            var registro = leer(metadata.topic(), metadata.partition(), metadata.offset(), mensaje -> mensaje.offset() == metadata.offset());
            var guardado = inbox.conservar(decoder.decodificar(new MensajeReserva(registro.topic(), registro.partition(), registro.offset(),
                    registro.key(), registro.value())));
            assertEquals(EstadoEventoReserva.RECIBIDO, guardado.estado());
            var recuperadorNuevo = new RecuperarResultadosReservaService(inbox, procesar);
            new ResultadosReservaScheduler(recuperadorNuevo).recuperar();
            assertEquals(EstadoEventoReserva.RESULTADO_APLICADO, inbox.buscarPorId(guardado.id()).orElseThrow().estado());
            listener.start(); ContainerTestUtils.waitForAssignment(listener, 1);
        }
        esperarCommit(metadata.offset()); esperarEstado(id, EstadoProcesoReserva.CONFIRMADA);
        var confirmado = procesos.buscarPorId(id).orElseThrow();
        esperarCommit(publicar(evento).offset());
        assertEquals(confirmado, procesos.buscarPorId(id).orElseThrow());
    }

    private void cancelarDesdeHttp(Sesion propietario) throws Exception {
        mvc.perform(autorizada(post("/api/reservas/" + caso.proceso + "/cancel"), propietario)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Cancelación de prueba\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("CANCELADA"));
    }

    private RecordMetadata publicar(ObjectNode body) throws Exception {
        return productor.send(ACCIONES, caso.proceso.getBytes(java.nio.charset.StandardCharsets.UTF_8), json.writeValueAsBytes(body))
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private ConsumerRecord<byte[], byte[]> leer(String topic, int particion, long desde, Predicate<ConsumerRecord<byte[], byte[]>> coincide) {
        var propiedades = Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "observador-" + UUID.randomUUID(), ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (var consumidor = new KafkaConsumer<>(propiedades, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            var asignada = new TopicPartition(topic, particion);
            consumidor.assign(List.of(asignada)); consumidor.seek(asignada, desde);
            var encontrado = new AtomicReference<ConsumerRecord<byte[], byte[]>>();
            await().pollInSameThread().atMost(Duration.ofSeconds(15)).until(() -> {
                for (var mensaje : consumidor.poll(Duration.ofMillis(100))) {
                    if (coincide.test(mensaje)) { encontrado.set(mensaje); return true; }
                }
                return false;
            });
            return encontrado.get();
        }
    }

    private void esperarEstado(long id, EstadoProcesoReserva esperado) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(esperado, procesos.buscarPorId(id).orElseThrow().estado()));
    }

    private void esperarCommit(long offset) {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                var confirmado = admin.listConsumerGroupOffsets(GRUPO_CONSUMIDOR).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS).get(new TopicPartition(ACCIONES, 0));
                return confirmado != null && confirmado.offset() > offset;
            });
        }
    }
}
