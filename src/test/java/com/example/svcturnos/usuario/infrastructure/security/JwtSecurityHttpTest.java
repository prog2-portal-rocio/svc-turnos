package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.AutenticarUsuarioUseCase;
import com.example.svcturnos.usuario.infrastructure.web.controller.AutenticacionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = {JwtSecurityHttpTest.ControladorProtegido.class, AutenticacionController.class}
)
@Import({
        SecurityConfig.class,
        JwtConfig.class,
        JwtTokenAdapter.class,
        JwtSecurityHttpTest.ControladorProtegido.class
})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
class JwtSecurityHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtDecoder decoder;

    @MockitoBean
    private AutenticarUsuarioUseCase autenticarUsuario;

    @RestController
    public static class ControladorProtegido {

        @GetMapping("/api/seguro")
        public String obtenerUsuario(Authentication authentication) {
            return authentication.getName();
        }
    }

    @Test
    void debeRechazarSolicitudSinToken() throws Exception {
        mockMvc.perform(get("/api/seguro"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void debePermitirSolicitudConJwtValido() throws Exception {
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

        String token = new JwtTokenAdapter(encoder)
                .generarToken(usuario);

        mockMvc.perform(get("/api/seguro")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));
    }

    @Test
    void debeRechazarTokenInvalido() throws Exception {
        mockMvc.perform(get("/api/seguro")
                        .header("Authorization", "Bearer token-invalido"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEmiteRs256YElTokenAutorizaUnaPeticionHttp() throws Exception {
        Usuario usuario = new Usuario(1L, "rocio", "hash-de-prueba", "Rocio", "Portal",
                "rocio@example.com", null, "es", true);
        when(autenticarUsuario.autenticar("rocio", "claveSegura123")).thenReturn(usuario);

        String respuesta = mockMvc.perform(post("/api/authenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"rocio", "password":"claveSegura123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonMapper.builder().build().readTree(respuesta).path("id_token").asString();

        assertEquals("RS256", decoder.decode(token).getHeaders().get("alg"));
        mockMvc.perform(get("/api/seguro").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));
    }

}
