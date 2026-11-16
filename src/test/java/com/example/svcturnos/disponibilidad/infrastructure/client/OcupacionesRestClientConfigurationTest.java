package com.example.svcturnos.disponibilidad.infrastructure.client;

import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException.Motivo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OcupacionesRestClientConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(OcupacionesRestClientConfiguration.class);
    private static final LocalDate FECHA = LocalDate.of(2026, 9, 14);

    @Test
    void sinConfiguracionNoInterrumpeElArranqueNiPermiteConsultar() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var adapter = new OcupacionesRestClientAdapter(context.getBean("ocupacionesRestClient", RestClient.class), "");
            var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
            assertEquals(Motivo.CONFIGURACION_INCOMPLETA, error.getMotivo());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://catedra", "http://usuario@catedra", "http://catedra?dato=1", "http://catedra/#dato", "relativa"})
    void rechazaUrlInvalida(String valor) {
        runner.withPropertyValues("turnos.catedra.base-url=" + valor).run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "99999999s"})
    void timeoutsNoPuedenSerIlimitados(String valor) {
        runner.withPropertyValues("turnos.catedra.connect-timeout=" + valor).run(context ->
                assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("turnos.catedra.response-timeout=" + valor).run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aplicaTimeoutAntesYDespuesDeLasCabeceras(boolean cabeceras) throws Exception {
        var recibida = new CountDownLatch(1);
        var terminar = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/appointment-occupancies", exchange -> {
            recibida.countDown();
            try {
                if (cabeceras) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                }
                terminar.await(5, TimeUnit.SECONDS);
                if (!cabeceras) { exchange.sendResponseHeaders(200, -1); }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            runner.withPropertyValues("turnos.catedra.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "turnos.catedra.response-timeout=200ms").run(context -> {
                try {
                    var adapter = new OcupacionesRestClientAdapter(context.getBean("ocupacionesRestClient", RestClient.class), "prueba");
                    var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
                    assertEquals(Motivo.TIEMPO_AGOTADO, error.getMotivo());
                    assertTrue(recibida.await(1, TimeUnit.SECONDS));
                } finally { terminar.countDown(); }
            });
        } finally {
            terminar.countDown();
            server.stop(0);
        }
    }

    @Test
    void noSigueRedireccionesNiEnviaElTokenAOtroDestino() throws Exception {
        var visitada = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/appointment-occupancies", exchange -> {
            exchange.getResponseHeaders().add("Location", "/otro-destino");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/otro-destino", exchange -> {
            visitada.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            runner.withPropertyValues("turnos.catedra.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        var adapter = new OcupacionesRestClientAdapter(context.getBean("ocupacionesRestClient", RestClient.class), "prueba");
                        var error = assertThrows(ConsultaOcupacionesException.class, () -> adapter.consultarOcupaciones(15L, FECHA));
                        assertEquals(Motivo.ERROR_SERVICIO, error.getMotivo());
                        assertFalse(visitada.get());
                    });
        } finally { server.stop(0); }
    }
}
