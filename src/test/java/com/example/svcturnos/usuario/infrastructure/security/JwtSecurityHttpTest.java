package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;



@WebMvcTest(
        controllers = JwtSecurityHttpTest.ControladorProtegido.class
)
@Import({
        SecurityConfig.class,
        JwtSecurityHttpTest.ConfiguracionDePrueba.class,
        JwtSecurityHttpTest.ControladorProtegido.class
})
class JwtSecurityHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder encoder;

    @RestController
    public static class ControladorProtegido {

        @GetMapping("/api/seguro")
        public String obtenerUsuario(Authentication authentication) {
            return authentication.getName();
        }
    }

    @TestConfiguration
    public static class ConfiguracionDePrueba {

        @Bean
        SecretKey claveDePrueba() {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return new SecretKeySpec(bytes, "HmacSHA256");
        }

        @Bean
        JwtEncoder jwtEncoder(SecretKey clave) {
            return new JwtConfig().jwtEncoder(clave);
        }

        @Bean
        JwtDecoder jwtDecoder(SecretKey clave) {
            return new JwtConfig().jwtDecoder(clave);
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
}
