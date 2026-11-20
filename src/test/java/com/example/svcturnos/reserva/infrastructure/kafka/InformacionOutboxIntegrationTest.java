package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.application.service.PublicarInformacionService;
import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.infrastructure.security.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.example.svcturnos.reserva.support.EventosReservaFixtures.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"turnos.kafka.integration-group-id=prueba-integracion",
        "turnos.kafka.phone-topic=catedra.turnos.telefono.prueba-integracion", "turnos.kafka.phone-publication-enabled=true",
        "turnos.kafka.outbox-initial-delay=3600000", "logging.level.org.apache.kafka=WARN"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers=JwtTestContextInitializer.class)
@Testcontainers
@DirtiesContext
@Import(InformacionOutboxIntegrationTest.Config.class)
class InformacionOutboxIntegrationTest {
    @Container @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18");
    @Container static KafkaContainer kafka=new KafkaContainer("apache/kafka:4.1.0");
    @DynamicPropertySource static void propiedades(DynamicPropertyRegistry r) { r.add("spring.kafka.bootstrap-servers",kafka::getBootstrapServers); }
    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean NewTopic telefono() { return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build(); }
        @Bean @Primary Reloj relojPrueba() { return new Reloj(); }
    }
    static class Reloj extends Clock {
        final AtomicReference<Instant> ahora=new AtomicReference<>(AHORA);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return ahora.get(); }
    }
    @Autowired Reloj reloj;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcesoReservaPersistencePort procesos;
    @Autowired InboxReservasPort inbox;
    @Autowired OutboxInformacionPort outbox;
    @Autowired EnviarInformacionUseCase enviar;
    @Autowired PublicarInformacionService publicador;
    @Autowired PublicarInformacionPort productor;
    @Autowired RecibirEventoReservaUseCase recibir;
    @Autowired EventoReservaDecoder decoder;
    final JsonMapper json=JsonMapper.builder().build();
    static final AtomicLong OFFSET=new AtomicLong(100000);
    static final Duration CONCESION=Duration.ofSeconds(60), DEMORA=Duration.ofSeconds(10);
    ProcesoReserva proceso;
    Long propietario;
    String token;
    EventoInbox solicitud;

    @BeforeEach void preparar() {
        reloj.ahora.set(AHORA);
        String login=UUID.randomUUID().toString();
        propietario=jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?,'hash-prueba','Nombre','Apellido',?) RETURNING id
                """,Long.class,login,login+"@test.invalid");
        var usuario=new Usuario(propietario,login,"hash-prueba","Nombre","Apellido",login+"@test.invalid",null,"es",true);
        token=new JwtTokenAdapter(encoder).generarToken(usuario);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtDecoder.decode(token),List.of()));
        proceso=procesos.guardar(new ProcesoReserva(null,propietario,15L,LocalDate.of(2026,12,1),LocalTime.of(9,30),
                EstadoProcesoReserva.ESPERANDO_TELEFONO,"hold-"+login,"externo-"+login,AHORA.plusSeconds(300),AHORA,AHORA,null));
        String body=solicitud(UUID.randomUUID().toString(),proceso.procesoExternoId(),proceso.holdId())
                .replace("2026-09-10T14:44:50Z",AHORA.plusSeconds(200).toString());
        solicitud=recibir.recibir(decoder.decodificar(mensaje(body,proceso.procesoExternoId(),OFFSET.incrementAndGet())));
    }
    @AfterEach void terminar() {
        SecurityContextHolder.clearContext();
        outbox.depurarVencidos(AHORA.plus(Duration.ofDays(2)));
    }
    String url() { return "/api/reservas/holds/"+proceso.id()+"/telefono"; }
    String body() { return "{\"phoneNumber\":\""+NUMERO+"\"}"; }
    EnvioInformacion aceptar() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwtDecoder.decode(token),List.of()));
        return enviar.enviar(proceso.id(),NUMERO);
    }
    String estado(UUID id) { return jdbc.queryForObject("SELECT estado FROM informacion_adicional_outbox WHERE event_id=?",String.class,id); }
    void sinTelefono(UUID id) { assertNull(jdbc.queryForObject("SELECT telefono FROM informacion_adicional_outbox WHERE event_id=?",String.class,id)); }

    List<JsonNode> mensajes(UUID evento,int cantidad) {
        var props=new HashMap<String,Object>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,UUID.randomUUID().toString());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        var resultado=new ArrayList<JsonNode>();
        try (var consumer=new KafkaConsumer<byte[],byte[]>(props,new ByteArrayDeserializer(),new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long limite=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while (resultado.size()<cantidad && System.nanoTime()<limite) {
                for (var mensajeKafka:consumer.poll(Duration.ofMillis(200))) {
                    var contenido=json.readTree(mensajeKafka.value());
                    if (evento.toString().equals(contenido.path("eventId").asString())) {
                        assertArrayEquals(contenido.path("reservationProcessId").asString().getBytes(java.nio.charset.StandardCharsets.UTF_8),mensajeKafka.key());
                        resultado.add(contenido);
                    }
                }
            }
        }
        assertEquals(cantidad,resultado.size());return resultado;
    }

    @Test void endpointAceptaLocalmenteYBrokerConfirmaSinConfirmarReserva() throws Exception {
        var response=mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("}",",\"usuarioId\":999,\"requestEventId\":\"inventado\"}")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.publicacion").value("PENDIENTE"))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist()).andReturn().getResponse().getContentAsString();
        var id=UUID.fromString(json.readTree(response).path("eventId").asString());
        assertEquals(1,publicador.publicarPendientes(10,5,CONCESION,DEMORA));
        assertEquals("ENVIADA",estado(id));sinTelefono(id);
        var mensaje=mensajes(id,1).getFirst();
        assertEquals(solicitud.evento().eventId(),mensaje.path("requestEventId").asString());
        assertEquals(NUMERO,mensaje.path("information").path("phoneNumber").asString());
        assertEquals(EstadoProcesoReserva.ESPERANDO_TELEFONO,procesos.buscarPorId(proceso.id()).orElseThrow().estado());
        var repetida=assertThrows(EnviarInformacionException.class,this::aceptar);
        assertEquals(EnviarInformacionException.Motivo.RESPUESTA_EXISTENTE,repetida.getMotivo());
    }
    @Test void dosPeticionesHttpConcurrentesConservanUnEventoLogico() throws Exception {
        var barrera=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<String> peticion=() -> {
                barrera.await(10,TimeUnit.SECONDS);
                return mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body()))
                        .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            };
            var uno=executor.submit(peticion);var dos=executor.submit(peticion);
            assertEquals(json.readTree(uno.get(20,TimeUnit.SECONDS)).path("eventId").asString(),
                    json.readTree(dos.get(20,TimeUnit.SECONDS)).path("eventId").asString());
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE proceso_id=?",Integer.class,proceso.id()));
    }
    @Test void timeoutDespuesDePublicarRecuperaMismoEventoDespuesDeReinicio() {
        var aceptado=aceptar();
        var sinAcuse=new PublicarInformacionService(outbox,envio -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            productor.publicar(envio);
            throw new IllegalStateException("acuse perdido");
        },reloj);
        sinAcuse.publicarPendientes(1,5,CONCESION,DEMORA);
        assertEquals("INCIERTA",estado(aceptado.eventId()));
        assertEquals(0,publicador.publicarPendientes(1,5,CONCESION,DEMORA));
        reloj.ahora.set(AHORA.plusSeconds(10));
        var reiniciado=new PublicarInformacionService(outbox,productor,reloj);
        assertEquals(1,reiniciado.publicarPendientes(1,5,CONCESION,DEMORA));
        var recibidos=mensajes(aceptado.eventId(),2);
        assertEquals(recibidos.getFirst(),recibidos.getLast());
        assertEquals("ENVIADA",estado(aceptado.eventId()));sinTelefono(aceptado.eventId());
    }
    @Test void caidaConConcesionActivaEsRecuperableSinNuevaIdentidad() {
        var aceptado=aceptar();
        var reclamado=outbox.reclamar(AHORA,CONCESION,5).orElseThrow();
        assertEquals(aceptado.eventId(),reclamado.eventId());
        assertTrue(outbox.reclamar(AHORA,CONCESION,5).isEmpty());
        reloj.ahora.set(AHORA.plusSeconds(60));
        assertEquals(1,publicador.publicarPendientes(1,5,CONCESION,DEMORA));
        assertEquals("ENVIADA",estado(aceptado.eventId()));
        mensajes(aceptado.eventId(),1);
        // Un callback de la concesión anterior no puede sobrescribir el resultado actual.
        outbox.registrarResultado(reclamado.eventId(),reclamado.intentoId(),false,reloj.instant(),DEMORA);
        assertEquals("ENVIADA",estado(aceptado.eventId()));
    }
    @Test void dosPublicadoresNoReclamanLaMismaIntencion() throws Exception {
        aceptar();var barrera=new CyclicBarrier(2);
        try (var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> reclamar=() -> { barrera.await(10,TimeUnit.SECONDS);return outbox.reclamar(AHORA,CONCESION,5).isPresent(); };
            var uno=executor.submit(reclamar);var dos=executor.submit(reclamar);
            assertEquals(List.of(false,true),List.of(uno.get(10,TimeUnit.SECONDS),dos.get(10,TimeUnit.SECONDS)).stream().sorted().toList());
        }
    }
    @Test void limiteDeIntentosEvitaBuclesYConservaIncertidumbre() {
        var aceptado=aceptar();
        var fallido=new PublicarInformacionService(outbox,envio -> { throw new IllegalStateException(); },reloj);
        for (int i=0;i<5;i++) {
            reloj.ahora.set(AHORA.plusSeconds(i*10L));assertEquals(1,fallido.publicarPendientes(1,5,CONCESION,DEMORA));
        }
        reloj.ahora.set(AHORA.plusSeconds(50));assertEquals(0,fallido.publicarPendientes(1,5,CONCESION,DEMORA));
        assertEquals("INCIERTA",estado(aceptado.eventId()));
        assertEquals(aceptado.eventId(),aceptar().eventId());
        reloj.ahora.set(AHORA.plusSeconds(200));outbox.depurarVencidos(reloj.instant());sinTelefono(aceptado.eventId());
    }
    @Test void vencimientoAntesDeEnviarDepuraTelefonoYNoPublica() {
        var aceptado=aceptar();reloj.ahora.set(AHORA.plusSeconds(200));
        assertEquals(0,publicador.publicarPendientes(10,5,CONCESION,DEMORA));
        assertEquals("VENCIDA",estado(aceptado.eventId()));sinTelefono(aceptado.eventId());
    }
    @Test void falloPostgresAntesDeAceptarNoDejaIntencionPublicable() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION simular_fallo_informacion() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'fallo simulado'; END; $$ LANGUAGE plpgsql
                """);
        String trigger="fallo_informacion_"+propietario;
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON informacion_adicional_outbox FOR EACH ROW WHEN (NEW.proceso_id="
                +proceso.id()+") EXECUTE FUNCTION simular_fallo_informacion()");
        try {
            assertThrows(EnviarInformacionException.class,this::aceptar);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE proceso_id=?",Integer.class,proceso.id()));
            assertEquals(0,publicador.publicarPendientes(10,5,CONCESION,DEMORA));
        } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON informacion_adicional_outbox"); }
    }
    @Test void falloAlGuardarAcuseConservaConcesionYRecuperaMismoId() {
        var aceptado=aceptar();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION simular_fallo_acuse() RETURNS trigger AS $$
                BEGIN IF NEW.estado='ENVIADA' THEN RAISE EXCEPTION 'fallo simulado'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql
                """);
        String trigger="fallo_acuse_"+propietario;
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON informacion_adicional_outbox FOR EACH ROW WHEN (NEW.proceso_id="
                +proceso.id()+") EXECUTE FUNCTION simular_fallo_acuse()");
        try {
            assertThrows(RuntimeException.class, () -> publicador.publicarPendientes(1,5,CONCESION,DEMORA));
            assertEquals("PUBLICANDO",estado(aceptado.eventId()));
        } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON informacion_adicional_outbox"); }
        reloj.ahora.set(AHORA.plusSeconds(60));publicador.publicarPendientes(1,5,CONCESION,DEMORA);
        assertEquals("ENVIADA",estado(aceptado.eventId()));mensajes(aceptado.eventId(),2);
    }
    @Test void sinJwtOConJwtInvalidoNoAceptaDatos() throws Exception {
        mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isUnauthorized());
        mvc.perform(post(url()).header("Authorization","Bearer invalido").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
    }
    @Test void usuarioAjenoNoPuedeResponder() throws Exception {
        String login=UUID.randomUUID().toString();
        Long otroId=jdbc.queryForObject("""
                INSERT INTO usuarios(login,password_hash,first_name,last_name,email)
                VALUES (?,'hash-prueba','Otro','Paciente',?) RETURNING id
                """,Long.class,login,login+"@test.invalid");
        var otro=new JwtTokenAdapter(encoder).generarToken(new Usuario(otroId,login,"hash","Otro","Paciente",login+"@test.invalid",null,"es",true));
        mvc.perform(post(url()).header("Authorization","Bearer "+otro).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"phoneNumber\":\"abc\"}","{\"phoneNumber\":\"123\"}"})
    void datosInvalidosSon400(String contenido) throws Exception {
        mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(contenido))
                .andExpect(status().isBadRequest());
    }
    @Test void solicitudPendienteNoSeInventaNiSePublica() throws Exception {
        jdbc.update("UPDATE eventos_reserva_inbox SET estado='PENDIENTE_CORRELACION',proceso_local_id=NULL WHERE id=?",solicitud.id());
        mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict());
    }
    @Test void identidadExternaInconsistenteNoPuedeUsarSolicitud() throws Exception {
        jdbc.update("UPDATE eventos_reserva_inbox SET hold_id='otro-hold' WHERE id=?",solicitud.id());
        mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict());
    }
    @Test void numeroDistintoNoSobrescribeIntencionExistente() {
        aceptar();var id=proceso.id();
        var error=assertThrows(EnviarInformacionException.class, () -> enviar.enviar(id,"1234567"));
        assertEquals(EnviarInformacionException.Motivo.RESPUESTA_EXISTENTE,error.getMotivo());
    }
    @Test void plazoVencidoNoAceptaNuevaIntencion() throws Exception {
        reloj.ahora.set(AHORA.plusSeconds(200));
        mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isGone());
    }
    @Test void duplicadoDeLaSolicitudConservaUnaSolaRespuestaLogica() {
        var original=inbox.buscarMensajePorId(solicitud.id()).orElseThrow();
        var duplicado=new MensajeReserva(original.topic(),original.particion(),OFFSET.incrementAndGet(),original.clave(),original.contenido());
        assertEquals(EstadoEventoReserva.DUPLICADO,recibir.recibir(decoder.decodificar(duplicado)).estado());
        var primero=aceptar();assertEquals(primero.eventId(),aceptar().eventId());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE proceso_id=?",Integer.class,proceso.id()));
    }
    @Test void publicarTelefonoNoLiberaOtroHoldEquivalente() {
        aceptar();publicador.publicarPendientes(1,5,CONCESION,DEMORA);
        var nuevo=new ProcesoReserva(null,propietario,proceso.profesionalId(),proceso.fecha(),proceso.hora(),
                EstadoProcesoReserva.HOLD_SOLICITADO,null,null,null,AHORA,AHORA,null);
        assertThrows(com.example.svcturnos.reserva.domain.exception.CrearHoldException.class, () -> procesos.guardar(nuevo));
    }
    @Test void estadoIncompatibleNoAceptaUnaIntencion() throws Exception {
        jdbc.update("UPDATE procesos_reserva SET estado='HOLD_REGISTRADO' WHERE id=?",proceso.id());
        mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest @ValueSource(strings={NUMERO,"1234567"})
    void telefonoYaDepuradoNoSePresentaComoNuevaAceptacion(String numero) throws Exception {
        var primero=aceptar();publicador.publicarPendientes(1,5,CONCESION,DEMORA);sinTelefono(primero.eventId());
        mvc.perform(post(url()).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\""+numero+"\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INFORMACION_RESPUESTA_EXISTENTE"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM informacion_adicional_outbox WHERE solicitud_id=?",Integer.class,solicitud.id()));
        assertEquals(primero.eventId(),jdbc.queryForObject("SELECT event_id FROM informacion_adicional_outbox WHERE solicitud_id=?",UUID.class,solicitud.id()));
        assertEquals("ENVIADA",estado(primero.eventId()));
    }

    @ParameterizedTest
    @CsvSource({"PENDIENTE,true,false", "PENDIENTE,false,true", "INCIERTA,true,false", "INCIERTA,false,true",
            "ENVIADA,true,false", "ENVIADA,false,true", "VENCIDA,true,false", "VENCIDA,false,true",
            "PUBLICANDO,true,false", "PUBLICANDO,false,true", "PUBLICANDO,false,false", "ENVIADA,true,true"})
    void concesionIncompletaOAjenaAlEstadoEsRechazada(String estado, boolean intento, boolean concesion) {
        var aceptado=aceptar();
        var id=aceptado.eventId();var intentoId=intento ? UUID.randomUUID() : null;
        var hasta=concesion ? java.sql.Timestamp.from(AHORA.plusSeconds(60)) : null;
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE informacion_adicional_outbox SET estado=?,intento_id=?,concesion_hasta=?,telefono=NULL WHERE event_id=?",
                estado,intentoId,hasta,id));
        assertEquals("PENDIENTE",estado(id));
    }

}
