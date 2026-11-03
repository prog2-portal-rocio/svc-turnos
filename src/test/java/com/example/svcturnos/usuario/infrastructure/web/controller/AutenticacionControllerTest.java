package com.example.svcturnos.usuario.infrastructure.web.controller;

import com.example.svcturnos.usuario.application.exception.CredencialesInvalidasException;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.AutenticarUsuarioUseCase;
import com.example.svcturnos.usuario.domain.ports.out.TokenPort;
import com.example.svcturnos.usuario.infrastructure.security.SecurityConfig;
import com.example.svcturnos.usuario.infrastructure.web.handler.UsuarioExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AutenticacionController.class)
@Import({SecurityConfig.class, UsuarioExceptionHandler.class})
class AutenticacionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AutenticarUsuarioUseCase autenticarUsuario;

    @MockitoBean
    private TokenPort tokenPort;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final String LOGIN_VALIDO = """
            {
                "username": "rocio",
                "password": "claveSegura123"
            }
            """;

    @Test
    void debeDevolverTokenCuandoLasCredencialesSonCorrectas()
            throws Exception {

        Usuario usuario = new Usuario(
                1L,
                "rocio",
                "hash-guardado",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es",
                true
        );

        when(autenticarUsuario.autenticar(
                "rocio", "claveSegura123"))
                .thenReturn(usuario);

        when(tokenPort.generarToken(usuario))
                .thenReturn("token-de-prueba");

        mockMvc.perform(post("/api/authenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token")
                        .value("token-de-prueba"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void debeResponder401ConCredencialesIncorrectas()
            throws Exception {

        when(autenticarUsuario.autenticar(
                "rocio", "claveSegura123"))
                .thenThrow(new CredencialesInvalidasException());

        mockMvc.perform(post("/api/authenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_VALIDO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("CREDENCIALES_INVALIDAS"));

        verifyNoInteractions(tokenPort);
    }

    @Test
    void debeResponder400CuandoFaltaLaContrasena()
            throws Exception {

        mockMvc.perform(post("/api/authenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "rocio"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(autenticarUsuario, tokenPort);
    }
}
