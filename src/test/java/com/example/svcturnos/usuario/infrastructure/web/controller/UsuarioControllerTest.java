package com.example.svcturnos.usuario.infrastructure.web.controller;

import com.example.svcturnos.usuario.application.exception.UsuarioDuplicadoException;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase;
import com.example.svcturnos.usuario.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.argThat;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@WebMvcTest(UsuarioController.class)
@Import(SecurityConfig.class)
class UsuarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private RegistrarUsuarioUseCase registrarUsuarioUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String REGISTRO_VALIDO = """
            {
                "login": "rocio",
                "password": "claveSegura123",
                "firstName": "Rocio",
                "lastName": "Portal",
                "email": "rocio@example.com",
                "imageUrl": null,
                "langKey": "es"
            }
            """;

    @ParameterizedTest
    @MethodSource("solicitudesRegistro")
    void debeRegistrarUsuarioYResponder201SinAceptarPermisosDelCliente(String solicitud) throws Exception {
        Usuario usuario = new Usuario(
                1L,
                "rocio",
                "hash-generado",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es",
                true
        );

        when(registrarUsuarioUseCase.registrar(any()))
                .thenReturn(usuario);

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(solicitud))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.login").value("rocio"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.scope").doesNotExist())
                .andExpect(jsonPath("$.roles").doesNotExist());

        verify(registrarUsuarioUseCase).registrar(new RegistrarUsuarioUseCase.DatosRegistro(
                "rocio", "claveSegura123", "Rocio", "Portal", "rocio@example.com", null, "es"));
    }

    private static Stream<String> solicitudesRegistro() {
        return Stream.of(REGISTRO_VALIDO, REGISTRO_VALIDO.replace(
                "\"langKey\": \"es\"",
                "\"langKey\": \"es\", \"scope\": \"catalogo:sync:read\", \"roles\": [\"ADMIN\"], \"activated\": false"));
    }

    @Test
    void debeRechazarRegistroConDatosInvalidos() throws Exception {
        String datosInvalidos = """
                {
                    "login": "rocio",
                    "password": "abc",
                    "firstName": "Rocio",
                    "lastName": "Portal",
                    "email": "correo-invalido"
                }
                """;

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(datosInvalidos))
                .andExpect(status().isBadRequest());
    }

    @Test
    void debeResponder409CuandoElUsuarioExiste() throws Exception {
        when(registrarUsuarioUseCase.registrar(any()))
                .thenThrow(new UsuarioDuplicadoException(
                        "El login ya está registrado"
                ));

        mockMvc.perform(post("/api/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("USUARIO_DUPLICADO"));
    }

    @Test
    void debeRechazarAccesoSinAutenticacion() throws Exception {
        mockMvc.perform(get("/api/privado"))
                .andExpect(status().isUnauthorized());
    }

    String conNombres(String nombre, String apellido) {
        return REGISTRO_VALIDO.replace("\"Rocio\"", json.writeValueAsString(nombre))
                .replace("\"Portal\"", json.writeValueAsString(apellido));
    }

    static Stream<Arguments> nombresInvalidos() {
        return Stream.of(Arguments.of("A", "Perez"), Arguments.of("Juan", "P"), Arguments.of(" A ", "Perez"),
                Arguments.of("Juan", " P "), Arguments.of("a".repeat(101), "Perez"), Arguments.of("Juan", "a".repeat(101)),
                Arguments.of(null, "Perez"), Arguments.of("Juan", null), Arguments.of("", "Perez"),
                Arguments.of("Juan", "\u2003"));
    }

    @ParameterizedTest @MethodSource("nombresInvalidos")
    void nombresIncompatiblesDevuelven400AntesDelCasoDeUso(String nombre, String apellido) throws Exception {
        mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(conNombres(nombre, apellido)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"));
        verifyNoInteractions(registrarUsuarioUseCase);
    }

    @ParameterizedTest @ValueSource(ints = {2, 50, 51, 100})
    void longitudSeValidaDespuesDeRecortarEspacios(int longitud) throws Exception {
        String nombre = "a".repeat(longitud);
        String apellido = "b".repeat(longitud);
        when(registrarUsuarioUseCase.registrar(any())).thenReturn(
                new Usuario(1L, "rocio", "hash-generado", nombre, apellido, "rocio@example.com", null, "es", true));
        mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content(conNombres("  " + nombre + "  ", "\t" + apellido + "\t")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.firstName").value(nombre))
                .andExpect(jsonPath("$.lastName").value(apellido));
        verify(registrarUsuarioUseCase).registrar(argThat(datos -> nombre.equals(datos.firstName()) && apellido.equals(datos.lastName())));
    }
}
