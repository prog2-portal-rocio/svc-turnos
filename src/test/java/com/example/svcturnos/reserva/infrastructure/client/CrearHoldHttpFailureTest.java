package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.disponibilidad.infrastructure.client.OcupacionesRestClientConfiguration;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CrearHoldHttpFailureTest {
    final SolicitudHold solicitud = new SolicitudHold(15L, LocalDate.of(2026, 12, 1), LocalTime.of(9, 30));

    CrearHoldRestAdapter adapter(int port) {
        var client = new OcupacionesRestClientConfiguration().ocupacionesRestClient("http://127.0.0.1:" + port,
                Duration.ofSeconds(1), Duration.ofMillis(200));
        return new CrearHoldRestAdapter(client, "tecnico-prueba");
    }

    @Test void servidorAceptaElPostPeroCierraSinRespuestaNoHayReenvioAutomatico() throws Exception {
        var contador = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/appointment-holds", exchange -> {
            exchange.getRequestBody().readAllBytes();
            contador.incrementAndGet();
            exchange.close();
        });
        server.start();
        try {
            var remoto = adapter(server.getAddress().getPort());
            var error = assertThrows(CrearHoldException.class, () -> remoto.crear(solicitud, "user-7"));
            assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
            assertEquals(1, contador.get());
        } finally { server.stop(0); }
    }

    @Test void timeoutDeRespuestaDespuesDeRecibirPostConservaIncertidumbre() throws Exception {
        var contador = new AtomicInteger();
        var terminar = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/appointment-holds", exchange -> {
            exchange.getRequestBody().readAllBytes();
            contador.incrementAndGet();
            try { terminar.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var remoto = adapter(server.getAddress().getPort());
            var error = assertThrows(CrearHoldException.class, () -> remoto.crear(solicitud, "user-7"));
            assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
            assertEquals(1, contador.get());
        } finally { terminar.countDown(); server.stop(0); }
    }
}
