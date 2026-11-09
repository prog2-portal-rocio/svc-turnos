package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

import static org.junit.jupiter.api.Assertions.*;

class JwtConfigTest {

    @TempDir
    Path directorio;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class)
            .withPropertyValues(
                    "turnos.jwt.private-key-location=" + JwtTestContextInitializer.ubicacionPrivada(),
                    "turnos.jwt.public-key-location=" + JwtTestContextInitializer.ubicacionPublica());

    @Test
    void cargaLosPemExternosYFirmaConElParConfigurado() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            Usuario usuario = new Usuario(1L, "ana", "hash", "Ana", "Perez", "ana@example.com", null, "es", true);
            String token = new JwtTokenAdapter(context.getBean(JwtEncoder.class)).generarToken(usuario);

            assertEquals("1", context.getBean(JwtDecoder.class).decode(token).getSubject());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"private", "public"})
    void impideArrancarCuandoFaltaUnaClave(String tipo) {
        String propiedadExistente = tipo.equals("private") ? "public" : "private";
        String ubicacion = tipo.equals("private")
                ? JwtTestContextInitializer.ubicacionPublica() : JwtTestContextInitializer.ubicacionPrivada();
        new ApplicationContextRunner().withUserConfiguration(JwtConfig.class)
                .withPropertyValues("turnos.jwt." + propiedadExistente + "-key-location=" + ubicacion)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"private", "public"})
    void impideArrancarConArchivoInexistente(String tipo) {
        runner.withPropertyValues("turnos.jwt." + tipo + "-key-location="
                        + directorio.resolve("inexistente.pem").toUri())
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"private", "public"})
    void impideArrancarConPemInvalido(String tipo) throws IOException {
        Path archivo = directorio.resolve("invalido.pem");
        Files.writeString(archivo, "No es una clave RSA PEM");
        runner.withPropertyValues("turnos.jwt." + tipo + "-key-location=" + archivo.toUri())
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void impideArrancarConClavesDeDistintosPares() {
        String otraPublica = JwtTestContextInitializer.guardarClave("PUBLIC KEY",
                JwtTestContextInitializer.generarClaves(2048).getPublic());

        runner.withPropertyValues("turnos.jwt.public-key-location=" + otraPublica)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void compruebaLaFirmaAunqueCoincidanLosModulos() throws GeneralSecurityException {
        RSAPublicKey publica = (RSAPublicKey) JwtTestContextInitializer.claves().getPublic();
        var incompatible = KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(publica.getModulus(), BigInteger.valueOf(3)));
        String archivo = JwtTestContextInitializer.guardarClave("PUBLIC KEY", incompatible);

        runner.withPropertyValues("turnos.jwt.public-key-location=" + archivo)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void impideArrancarConClavesMenoresA2048Bits() {
        var claves = JwtTestContextInitializer.generarClaves(1024);
        String privada = JwtTestContextInitializer.guardarClave("PRIVATE KEY", claves.getPrivate());
        String publica = JwtTestContextInitializer.guardarClave("PUBLIC KEY", claves.getPublic());

        runner.withPropertyValues("turnos.jwt.private-key-location=" + privada,
                        "turnos.jwt.public-key-location=" + publica)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
