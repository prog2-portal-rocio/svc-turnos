package com.example.svcturnos.usuario.infrastructure.security;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;

public class JwtTestContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final KeyPair CLAVES = generarClaves(2048);
    private static final String PRIVADA = guardarClave("PRIVATE KEY", CLAVES.getPrivate());
    private static final String PUBLICA = guardarClave("PUBLIC KEY", CLAVES.getPublic());

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "jwt-rsa-de-prueba", Map.of(
                        "turnos.jwt.private-key-location", PRIVADA,
                        "turnos.jwt.public-key-location", PUBLICA
                )));
    }

    static KeyPair claves() {
        return CLAVES;
    }

    static String ubicacionPrivada() {
        return PRIVADA;
    }

    static String ubicacionPublica() {
        return PUBLICA;
    }

    static KeyPair generarClaves(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudieron generar las claves RSA de prueba", e);
        }
    }

    static String guardarClave(String tipo, Key clave) {
        try {
            var archivo = Files.createTempFile("turnos-jwt-", ".pem");
            archivo.toFile().deleteOnExit();
            String contenido = "-----BEGIN " + tipo + "-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(clave.getEncoded())
                    + "\n-----END " + tipo + "-----\n";
            Files.writeString(archivo, contenido, StandardCharsets.US_ASCII);
            return archivo.toUri().toString();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar la clave temporal de prueba", e);
        }
    }
}
