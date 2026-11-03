package com.example.svcturnos.usuario.infrastructure.web.controller;

import com.example.svcturnos.usuario.application.exception.UsuarioDuplicadoException;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase;
import com.example.svcturnos.usuario.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@WebMvcTest(UsuarioController.class)
@Import(SecurityConfig.class)
class UsuarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void debeRegistrarUsuarioYResponder201() throws Exception {
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
                        .content(REGISTRO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.login").value("rocio"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
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
}
