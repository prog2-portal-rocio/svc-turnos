package com.example.svcturnos.reserva.infrastructure.client;

import com.example.svcturnos.disponibilidad.infrastructure.client.OcupacionesRestClientConfiguration;
import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.svcturnos.reserva.domain.exception.ConfirmarHoldException;
import com.example.svcturnos.reserva.domain.model.PacienteReserva;
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

    void comprobarResultadoIncierto(int port, boolean confirmar) {
        if (confirmar) {
            var client = new OcupacionesRestClientConfiguration().ocupacionesRestClient("http://127.0.0.1:" + port,
                    Duration.ofSeconds(1), Duration.ofMillis(200));
            var remoto = new ConfirmarHoldRestAdapter(client, "tecnico-prueba");
            var paciente = new PacienteReserva(7L, "Juan", "Perez");
            var error = assertThrows(ConfirmarHoldException.class, () -> remoto.confirmar("hold", paciente));
            assertEquals(ConfirmarHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
        } else {
            var remoto = adapter(port);
            var error = assertThrows(CrearHoldException.class, () -> remoto.crear(solicitud, "user-7"));
            assertEquals(CrearHoldException.Motivo.RESULTADO_INCIERTO, error.getMotivo());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void servidorAceptaElPostPeroCierraSinRespuestaNoHayReenvioAutomatico(boolean confirmar) throws Exception {
        var contador = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/appointment-holds", exchange -> {
            exchange.getRequestBody().readAllBytes();
            contador.incrementAndGet();
            exchange.close();
        });
        server.start();
        try {
            comprobarResultadoIncierto(server.getAddress().getPort(), confirmar);
            assertEquals(1, contador.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void timeoutDeRespuestaDespuesDeRecibirPostConservaIncertidumbre(boolean confirmar) throws Exception {
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
            comprobarResultadoIncierto(server.getAddress().getPort(), confirmar);
            assertEquals(1, contador.get());
        } finally { terminar.countDown(); server.stop(0); }
    }
}
