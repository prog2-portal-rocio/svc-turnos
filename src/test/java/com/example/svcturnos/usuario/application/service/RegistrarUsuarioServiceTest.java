package com.example.svcturnos.usuario.application.service;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase.DatosRegistro;
import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegistrarUsuarioServiceTest {

    private final UsuarioPersistencePort usuarios =
            mock(UsuarioPersistencePort.class);

    private final PasswordHasherPort hasher =
            mock(PasswordHasherPort.class);

    private final RegistrarUsuarioService service =
            new RegistrarUsuarioService(usuarios, hasher);

    private DatosRegistro datosValidos() {
        return new DatosRegistro(
                "rocio",
                "claveSegura123",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es"
        );
    }

    @Test
    void debeRegistrarUsuarioConContrasenaHasheada() {
        when(hasher.hash("claveSegura123"))
                .thenReturn("hash-generado");

        when(usuarios.guardar(any(Usuario.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Usuario resultado = service.registrar(datosValidos());

        assertEquals("rocio", resultado.login());
        assertEquals("hash-generado", resultado.passwordHash());
        assertTrue(resultado.activated());

        verify(hasher).hash("claveSegura123");
        verify(usuarios).guardar(any(Usuario.class));
    }

    @Test
    void debeRechazarLoginDuplicado() {
        when(usuarios.existeLogin("rocio")).thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registrar(datosValidos())
        );

        verify(usuarios, never()).guardar(any());
        verifyNoInteractions(hasher);
    }

    @Test
    void debeRechazarEmailDuplicado() {
        when(usuarios.existeEmail("rocio@example.com"))
                .thenReturn(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registrar(datosValidos())
        );

        verify(usuarios, never()).guardar(any());
        verifyNoInteractions(hasher);
    }
}
