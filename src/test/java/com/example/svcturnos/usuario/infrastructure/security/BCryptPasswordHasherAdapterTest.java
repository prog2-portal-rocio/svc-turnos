
package com.example.svcturnos.usuario.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BCryptPasswordHasherAdapterTest {

    private final BCryptPasswordHasherAdapter hasher =
            new BCryptPasswordHasherAdapter();

    @Test
    void debeGenerarUnHashDistintoDeLaContrasenaOriginal() {
        String password = "claveSegura123";

        String hash = hasher.hash(password);

        assertNotNull(hash);
        assertNotEquals(password, hash);
        assertTrue(hasher.matches(password, hash));
    }

    @Test
    void debeRechazarUnaContrasenaIncorrecta() {
        String hash = hasher.hash("claveCorrecta123");

        assertFalse(hasher.matches("claveIncorrecta", hash));
    }

    @Test
    void debeGenerarHashesDiferentesParaLaMismaContrasena() {
        String password = "claveSegura123";

        String primerHash = hasher.hash(password);
        String segundoHash = hasher.hash(password);

        assertNotEquals(primerHash, segundoHash);
        assertTrue(hasher.matches(password, primerHash));
        assertTrue(hasher.matches(password, segundoHash));
    }
}
