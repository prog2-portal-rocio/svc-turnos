package com.example.svcturnos.reserva.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.svcturnos.reserva.support.InformacionFixtures.NUMERO;

/** Dobles HTTP de Catálogo y cátedra. No reemplaza puertos ni componentes de Turnos. */
public final class IntegracionRestSimulada implements AutoCloseable {
    public static final String CREDENCIAL_FICTICIA = "tecnico-ficticio-sin-validez-externa";
    private static final AtomicLong RESERVAS = new AtomicLong(8000);
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Caso> actual = new AtomicReference<>();
    private final ConcurrentLinkedQueue<String> fallos = new ConcurrentLinkedQueue<>();
    private HttpServer servidor;

    public synchronized void iniciar() throws IOException {
        if (servidor != null) { return; }
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/", this::atender);
        servidor.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + servidor.getAddress().getPort(); }

    public Caso preparar(Long usuario, String jwt, boolean perderRespuesta, Clock reloj) {
        fallos.clear();
        var caso = new Caso(usuario, jwt, perderRespuesta, reloj);
        actual.set(caso);
        return caso;
    }

    public void verificar() { assertTrue(fallos.isEmpty(), () -> "Solicitudes externas incompatibles: " + fallos); }

    @Override public void close() { if (servidor != null) { servidor.stop(0); } }

    private void atender(HttpExchange intercambio) throws IOException {
        try {
            var caso = actual.get();
            assertNotNull(caso, "No se configuró el escenario HTTP");
            if (intercambio.getRequestURI().getPath().startsWith("/api/catalogo/")) { catalogo(intercambio, caso); }
            else { catedra(intercambio, caso); }
        } catch (IOException | RuntimeException | AssertionError error) {
            // No conservar bodies, teléfonos, headers ni mensajes de excepción en el diagnóstico.
            fallos.add(intercambio.getRequestURI().getPath() + ": " + error.getClass().getSimpleName());
            responder(intercambio, 500, "{\"code\":\"SIMULADOR_CONTRATO_INVALIDO\"}");
        } finally { intercambio.close(); }
    }

    private void catalogo(HttpExchange intercambio, Caso caso) throws IOException {
        assertEquals("GET", intercambio.getRequestMethod());
        assertTrue(("Bearer " + caso.jwt).equals(intercambio.getRequestHeaders().getFirst("Authorization")),
                "Catálogo debe recibir el JWT del usuario");
        switch (intercambio.getRequestURI().getPath()) {
            case "/api/catalogo/profesionales/15" -> responder(intercambio, 200, """
                    {"id":15,"categoryId":1,"firstName":"Ana","lastName":"Gomez","enabled":true,
                     "category":{"id":1,"name":"Clinica","enabled":true}}
                    """);
            case "/api/catalogo/profesionales/15/horarios" -> responder(intercambio, 200, """
                    [{"id":21,"professionalId":15,"dayOfWeek":"TUESDAY","startTime":"09:00",
                      "endTime":"10:30:00","slotDurationMinutes":30,"enabled":true}]
                    """);
            default -> fail("Ruta de Catálogo no prevista");
        }
    }

    private void catedra(HttpExchange intercambio, Caso caso) throws IOException {
        assertTrue(("Bearer " + CREDENCIAL_FICTICIA).equals(intercambio.getRequestHeaders().getFirst("Authorization")),
                "Cátedra debe recibir la credencial técnica ficticia, no el JWT del usuario");
        String ruta = intercambio.getRequestURI().getPath();
        switch (ruta) {
            case "/api/appointment-occupancies" -> ocupaciones(intercambio, caso);
            case "/api/appointment-holds" -> crearHold(intercambio, caso);
            case "/api/appointments" -> listar(intercambio, caso);
            default -> operacionSobreReserva(intercambio, caso, ruta);
        }
    }

    private void ocupaciones(HttpExchange intercambio, Caso caso) throws IOException {
        assertEquals("GET", intercambio.getRequestMethod());
        var parametros = parametros(intercambio);
        assertEquals("15", parametros.get("professionalId"));
        assertEquals("2026-12-01", parametros.get("from"));
        assertEquals("2026-12-01", parametros.get("to"));
        responder(intercambio, 200, """
                {"professionalId":15,"from":"2026-12-01","to":"2026-12-01","occupancies":[
                 {"date":"2026-12-01","startTime":"09:00","status":"CONFIRMED","expiresAt":null},
                 {"date":"2026-12-01","startTime":"10:00","status":"HELD","expiresAt":"%s"}]}
                """.formatted(caso.vence));
    }

    private JsonNode cuerpo(HttpExchange intercambio) throws IOException {
        assertEquals("POST", intercambio.getRequestMethod());
        assertTrue(intercambio.getRequestHeaders().getFirst("Content-Type").startsWith("application/json"));
        return json.readTree(intercambio.getRequestBody());
    }

    private void crearHold(HttpExchange intercambio, Caso caso) throws IOException {
        var body = cuerpo(intercambio);
        assertEquals(caso.paciente, body.path("externalPatientId").asString());
        assertEquals(15, body.path("professionalId").asInt());
        assertEquals("2026-12-01", body.path("date").asString());
        assertEquals(java.time.LocalTime.of(9, 30), java.time.LocalTime.parse(body.path("startTime").asString()));
        caso.holds.incrementAndGet();
        responder(intercambio, 201, json.createObjectNode().put("holdId", caso.hold).put("reservationProcessId", caso.proceso)
                .put("status", "HELD").put("expiresAt", caso.vence.toString()).toString());
    }

    private void operacionSobreReserva(HttpExchange intercambio, Caso caso, String ruta) throws IOException {
        if (ruta.equals("/api/appointment-holds/" + caso.hold + "/confirm")) {
            var body = cuerpo(intercambio);
            assertEquals(caso.paciente, body.path("externalPatientId").asString());
            assertEquals("María", body.path("patientFirstName").asString());
            assertEquals("Perez", body.path("patientLastName").asString());
            caso.confirmaciones.incrementAndGet();
            caso.estado.set("PHONE_PENDING");
            if (!caso.perderRespuesta) {
                responder(intercambio, 202, json.createObjectNode().put("reservationProcessId", caso.proceso)
                        .put("status", "WAITING_FOR_PHONE").put("expiresAt", caso.vence.toString()).toString());
            }
            // Cerrar sin respuesta simula aceptación remota con respuesta perdida.
        } else if (ruta.equals("/api/appointments/" + caso.proceso + "/cancel")) {
            assertEquals("Cancelación de prueba", cuerpo(intercambio).path("reason").asString());
            caso.cancelaciones.incrementAndGet();
            caso.canceladoEn = caso.reloj.instant();
            caso.estado.set("CANCELLED");
            responder(intercambio, 200, json.createObjectNode().put("reservationId", caso.reserva).put("reservationProcessId", caso.proceso)
                    .put("status", "CANCELLED").put("cancelledAt", caso.canceladoEn.toString()).toString());
        } else { fail("Operación externa no prevista"); }
    }

    private void listar(HttpExchange intercambio, Caso caso) throws IOException {
        assertEquals("GET", intercambio.getRequestMethod());
        var parametros = parametros(intercambio);
        assertNotNull(parametros.get("externalPatientId"), "La consulta debe filtrar por paciente");
        assertEquals("0", parametros.get("page"));
        assertTrue(java.util.Set.of("PHONE_PENDING", "CONFIRMED", "CANCELLED", "FAILED").contains(parametros.get("status")));
        var contenido = json.createArrayNode();
        if (caso.paciente.equals(parametros.get("externalPatientId")) && caso.estado.get().equals(parametros.get("status"))) {
            var reserva = contenido.addObject().put("reservationId", caso.reserva).put("reservationProcessId", caso.proceso)
                    .put("externalPatientId", caso.paciente).put("appointmentDate", "2026-12-01").put("startTime", "09:30:00")
                    .put("endTime", "10:00:00").put("professionalId", 15).put("professionalFirstName", "Ana")
                    .put("professionalLastName", "Gomez").put("patientFirstName", "María").put("patientLastName", "Perez")
                    .put("status", caso.estado.get()).put("createdAt", caso.creado.toString());
            reserva.put("patientPhone", caso.confirmadoEn == null ? null : NUMERO);
            reserva.put("confirmedAt", caso.confirmadoEn == null ? null : caso.confirmadoEn.toString());
            reserva.put("cancelledAt", caso.canceladoEn == null ? null : caso.canceladoEn.toString());
            reserva.put("cancellationReason", caso.canceladoEn == null ? null : "Cancelación de prueba");
        }
        intercambio.getResponseHeaders().set("X-Total-Count", Integer.toString(contenido.size()));
        intercambio.getResponseHeaders().set("Link", "<" + baseUrl() + intercambio.getRequestURI() + ">; rel=\"self\"");
        responder(intercambio, 200, contenido.toString());
    }

    private Map<String, String> parametros(HttpExchange intercambio) {
        var resultado = new HashMap<String, String>();
        for (var parametro : intercambio.getRequestURI().getRawQuery().split("&")) {
            var partes = parametro.split("=", 2);
            resultado.put(URLDecoder.decode(partes[0], StandardCharsets.UTF_8), URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
        }
        return resultado;
    }

    private void responder(HttpExchange intercambio, int estado, String contenido) throws IOException {
        byte[] bytes = contenido.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().set("Content-Type", "application/json");
        intercambio.sendResponseHeaders(estado, bytes.length);
        intercambio.getResponseBody().write(bytes);
    }

    public static final class Caso {
        public final String hold = "hold-" + UUID.randomUUID();
        public final String proceso = "process-" + UUID.randomUUID();
        public final long reserva = RESERVAS.incrementAndGet();
        public final Instant creado;
        public final Instant vence;
        public final AtomicInteger holds = new AtomicInteger();
        public final AtomicInteger confirmaciones = new AtomicInteger();
        public final AtomicInteger cancelaciones = new AtomicInteger();
        private final AtomicReference<String> estado = new AtomicReference<>("HELD");
        private volatile Instant confirmadoEn;
        private volatile Instant canceladoEn;
        private final String paciente;
        private final String jwt;
        private final boolean perderRespuesta;
        private final Clock reloj;

        public void confirmarReserva() {
            confirmadoEn = reloj.instant();
            estado.set("CONFIRMED");
        }

        public void expirarProceso() { estado.set("FAILED"); }

        private Caso(Long usuario, String jwt, boolean perderRespuesta, Clock reloj) {
            this.paciente = "user-" + usuario;
            this.jwt = jwt;
            this.perderRespuesta = perderRespuesta;
            this.reloj = reloj;
            this.creado = reloj.instant();
            this.vence = creado.plusSeconds(600);
        }
    }
}
