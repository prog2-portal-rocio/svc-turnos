package com.example.svcturnos.catalogo.infrastructure.client;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException.Motivo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CatalogoRestClientConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(CatalogoRestClientConfiguration.class)
            .withPropertyValues("turnos.catalogo.base-url=http://localhost:8081",
                    "turnos.catalogo.connect-timeout=2s", "turnos.catalogo.response-timeout=3s");

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void creaElClienteConConfiguracionExternaValida() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean("catalogoRestClient", RestClient.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void noPermiteTimeoutDeConexionIlimitado(String valor) {
        runner.withPropertyValues("turnos.catalogo.connect-timeout=" + valor).run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void noPermiteTimeoutDeRespuestaIlimitado(String valor) {
        runner.withPropertyValues("turnos.catalogo.response-timeout=" + valor).run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "catalogo", "ftp://catalogo", "http://usuario@catalogo", "http://catalogo?a=1", "http://catalogo/#fragmento"})
    void rechazaUrlInvalidaOConDatosQueNoPertenecenALaUrlBase(String url) {
        runner.withPropertyValues("turnos.catalogo.base-url=" + url).run(context ->
                assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usaLaUrlConfiguradaYAplicaTimeoutAntesYDespuesDeLasCabeceras(boolean cabecerasEnviadas) throws Exception {
        var recibida = new CountDownLatch(1);
        var responder = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/catalogo/profesionales/15", exchange -> {
            recibida.countDown();
            try {
                if (cabecerasEnviadas) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                }
                responder.await(5, TimeUnit.SECONDS);
                if (!cabecerasEnviadas) {
                    exchange.sendResponseHeaders(200, -1);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var jwt = Jwt.withTokenValue("jwt-de-prueba").header("alg", "RS256").subject("7").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            runner.withPropertyValues("turnos.catalogo.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                    "turnos.catalogo.response-timeout=300ms").run(context -> {
                try {
                    var adapter = new CatalogoRestClientAdapter(context.getBean("catalogoRestClient", RestClient.class));
                    var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(15L));
                    assertEquals(Motivo.TIEMPO_AGOTADO, error.getMotivo());
                    assertTrue(recibida.await(1, TimeUnit.SECONDS));
                } finally {
                    responder.countDown();
                }
            });
        } finally {
            responder.countDown();
            server.stop(0);
        }
    }

    @Test
    void noSigueRedireccionesConElBearerDelUsuario() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redireccionada = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/api/catalogo/profesionales/15", exchange -> {
            exchange.getResponseHeaders().add("Location", "/otra-ruta");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/otra-ruta", exchange -> {
            redireccionada.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        var jwt = Jwt.withTokenValue("jwt-de-prueba").header("alg", "RS256").subject("7").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        try {
            runner.withPropertyValues("turnos.catalogo.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        var adapter = new CatalogoRestClientAdapter(context.getBean("catalogoRestClient", RestClient.class));
                        var error = assertThrows(ConsultaCatalogoException.class, () -> adapter.consultarProfesional(15L));
                        assertEquals(Motivo.ERROR_SERVICIO, error.getMotivo());
                        assertFalse(redireccionada.get());
                    });
        } finally {
            server.stop(0);
        }
    }
}
