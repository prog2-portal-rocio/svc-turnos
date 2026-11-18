package com.example.svcturnos.reserva.infrastructure.security;

import com.example.svcturnos.reserva.domain.exception.CrearHoldException;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsuarioAutenticadoAdapterTest {
    final UsuarioPersistencePort usuarios = mock(UsuarioPersistencePort.class);
    final UsuarioAutenticadoAdapter adapter = new UsuarioAutenticadoAdapter(usuarios);

    @AfterEach void limpiar() { SecurityContextHolder.clearContext(); }

    void autenticar(String sub, String login, boolean validado) {
        var builder = Jwt.withTokenValue("validado-prueba").header("alg", "RS256").subject(sub);
        if (login != null) { builder.claim("login", login); }
        var auth = new JwtAuthenticationToken(builder.build(), List.of());
        auth.setAuthenticated(validado);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test void contextoSinJwtValidadoNoIdentificaUsuario() {
        var error = assertThrows(CrearHoldException.class, adapter::obtenerUsuarioId);
        assertEquals(CrearHoldException.Motivo.NO_AUTENTICADO, error.getMotivo());
        autenticar("7", "prueba", false);
        assertThrows(CrearHoldException.class, adapter::obtenerUsuarioId);
        verifyNoInteractions(usuarios);
    }

    @ParameterizedTest @ValueSource(strings = {"no-es-id", "0", "-1", "9223372036854775808"})
    void subNoPuedeReferenciarIdentidadesInvalidas(String sub) {
        autenticar(sub, "prueba", true);
        var error = assertThrows(CrearHoldException.class, adapter::obtenerUsuarioId);
        assertEquals(CrearHoldException.Motivo.NO_AUTORIZADO, error.getMotivo());
        verifyNoInteractions(usuarios);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", " "})
    void noAceptaJwtSinLoginCorrespondiente(String login) {
        autenticar("7", login, true);
        assertThrows(CrearHoldException.class, adapter::obtenerUsuarioId);
        verifyNoInteractions(usuarios);
    }
}
