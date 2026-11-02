package com.example.svcturnos.usuario.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UsuarioTest {

    @Test
    void debeConservarLosDatosDelUsuario() {

        Usuario usuario = new Usuario(
                1L,
                "rocio",
                "hash-de-prueba",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es",
                true
        );

        assertEquals(1L, usuario.id());
        assertEquals("rocio", usuario.login());
        assertEquals("hash-de-prueba", usuario.passwordHash());
        assertEquals("Rocio", usuario.firstName());
        assertEquals("Portal", usuario.lastName());
        assertEquals("rocio@example.com", usuario.email());
        assertNull(usuario.imageUrl());
        assertEquals("es", usuario.langKey());
        assertTrue(usuario.activated());
    }
}
