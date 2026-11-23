package com.example.svcturnos.usuario.application.service;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase.DatosRegistro;
import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.svcturnos.reserva.domain.model.PacienteReserva;
import java.util.stream.Stream;
import com.example.svcturnos.usuario.application.exception.UsuarioDuplicadoException;

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

        DatosRegistro datos = datosValidos();

        assertThrows(
                UsuarioDuplicadoException.class,
                () -> service.registrar(datos)
        );

        verify(usuarios, never()).guardar(any());
        verifyNoInteractions(hasher);
    }

    @Test
    void debeRechazarEmailDuplicado() {
        when(usuarios.existeEmail("rocio@example.com"))
                .thenReturn(true);

        DatosRegistro datos = datosValidos();

        assertThrows(
                UsuarioDuplicadoException.class,
                () -> service.registrar(datos)
        );

        verify(usuarios, never()).guardar(any());
        verifyNoInteractions(hasher);
    }

    @Test
    void debeRechazarContrasenaDemasiadoCorta() {
        DatosRegistro datos = new DatosRegistro(
                "rocio",
                "abc",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registrar(datos)
        );

        verify(usuarios, never()).guardar(any());
    }

    @Test
    void debeRechazarEmailConFormatoInvalido() {
        DatosRegistro datos = new DatosRegistro(
                "rocio",
                "claveSegura123",
                "Rocio",
                "Portal",
                "correo-invalido",
                null,
                "es"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.registrar(datos)
        );

        verify(usuarios, never()).guardar(any());
    }

    DatosRegistro conNombres(String nombre, String apellido) {
        var base = datosValidos();
        return new DatosRegistro(base.login(), base.password(), nombre, apellido, base.email(), base.imageUrl(), base.langKey());
    }

    static Stream<Arguments> nombresInvalidos() {
        return Stream.of(Arguments.of("A", "Perez"), Arguments.of("Juan", "P"), Arguments.of(" A ", "Perez"),
                Arguments.of("Juan", " P "), Arguments.of("a".repeat(101), "Perez"),
                Arguments.of("Juan", "a".repeat(101)), Arguments.of("\u2003", "Perez"));
    }

    @ParameterizedTest @MethodSource("nombresInvalidos")
    void noPersisteNiHasheaDatosIncompatiblesConElPaciente(String nombre, String apellido) {
        var datos = conNombres(nombre, apellido);
        assertThrows(IllegalArgumentException.class, () -> service.registrar(datos));
        verifyNoInteractions(usuarios, hasher);
    }

    @ParameterizedTest @ValueSource(ints = {2, 50, 51, 100})
    void registroNormalizadoEsCompatibleConPacienteEnTodoElIntervalo(int longitud) {
        String nombre = "a".repeat(longitud);
        String apellido = "b".repeat(longitud);
        when(hasher.hash(any())).thenReturn("hash-generado");
        when(usuarios.guardar(any())).thenAnswer(invocation -> {
            Usuario nuevo = invocation.getArgument(0);
            return new Usuario(7L, nuevo.login(), nuevo.passwordHash(), nuevo.firstName(), nuevo.lastName(), nuevo.email(),
                    nuevo.imageUrl(), nuevo.langKey(), nuevo.activated());
        });
        var registrado = service.registrar(conNombres("  " + nombre + "  ", "\t" + apellido + "\t"));
        var paciente = new PacienteReserva(registrado.id(), registrado.firstName(), registrado.lastName());
        assertEquals(nombre, registrado.firstName());
        assertEquals(apellido, registrado.lastName());
        assertEquals(nombre, paciente.patientFirstName());
        assertEquals(apellido, paciente.patientLastName());
        assertEquals("user-7", paciente.externalPatientId());
    }
}
