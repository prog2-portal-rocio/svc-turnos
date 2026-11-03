package com.example.svcturnos.usuario.application.service;

import com.example.svcturnos.usuario.application.exception.CredencialesInvalidasException;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutenticarUsuarioServiceTest {

    private final UsuarioPersistencePort usuarios =
            mock(UsuarioPersistencePort.class);

    private final PasswordHasherPort passwordHasher =
            mock(PasswordHasherPort.class);

    private final AutenticarUsuarioService service =
            new AutenticarUsuarioService(usuarios, passwordHasher);

    private Usuario crearUsuario(boolean activo) {
        return new Usuario(
                1L,
                "rocio",
                "hash-guardado",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es",
                activo
        );
    }

    @Test
    void debeAutenticarUsuarioConCredencialesCorrectas() {
        Usuario usuario = crearUsuario(true);

        when(usuarios.buscarPorLogin("rocio"))
                .thenReturn(Optional.of(usuario));

        when(passwordHasher.matches(
                "claveSegura123", "hash-guardado"))
                .thenReturn(true);

        Usuario resultado = service.autenticar(
                "rocio", "claveSegura123");

        assertEquals(usuario, resultado);

        verify(passwordHasher).matches(
                "claveSegura123", "hash-guardado");
    }

    @Test
    void debeRechazarContrasenaIncorrecta() {
        when(usuarios.buscarPorLogin("rocio"))
                .thenReturn(Optional.of(crearUsuario(true)));

        when(passwordHasher.matches(
                "claveIncorrecta", "hash-guardado"))
                .thenReturn(false);

        assertThrows(
                CredencialesInvalidasException.class,
                () -> service.autenticar(
                        "rocio", "claveIncorrecta")
        );
    }

    @Test
    void debeRechazarUsuarioInexistente() {
        when(usuarios.buscarPorLogin("desconocido"))
                .thenReturn(Optional.empty());

        assertThrows(
                CredencialesInvalidasException.class,
                () -> service.autenticar(
                        "desconocido", "claveSegura123")
        );

        verifyNoInteractions(passwordHasher);
    }

    @Test
    void debeRechazarUsuarioDesactivado() {
        when(usuarios.buscarPorLogin("rocio"))
                .thenReturn(Optional.of(crearUsuario(false)));

        assertThrows(
                CredencialesInvalidasException.class,
                () -> service.autenticar(
                        "rocio", "claveSegura123")
        );

        verifyNoInteractions(passwordHasher);
    }
}
