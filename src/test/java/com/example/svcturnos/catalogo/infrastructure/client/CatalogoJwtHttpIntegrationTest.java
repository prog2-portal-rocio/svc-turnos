package com.example.svcturnos.catalogo.infrastructure.client;

import com.example.svcturnos.catalogo.domain.ports.out.ConsultarCatalogoPort;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.infrastructure.security.JwtConfig;
import com.example.svcturnos.usuario.infrastructure.security.JwtTestContextInitializer;
import com.example.svcturnos.usuario.infrastructure.security.JwtTokenAdapter;
import com.example.svcturnos.usuario.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CatalogoJwtHttpIntegrationTest.ControladorDePrueba.class)
@Import({SecurityConfig.class, JwtConfig.class, CatalogoRestClientAdapter.class,
        CatalogoJwtHttpIntegrationTest.ControladorDePrueba.class, CatalogoJwtHttpIntegrationTest.ClienteSimulado.class})
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
class CatalogoJwtHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder encoder;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private MockRestServiceServer server;

    @AfterEach
    void verificarSolicitudes() {
        server.verify();
    }

    @Test
    void propagaElTokenRealEmitidoYValidadoPorLaCadenaHttp() throws Exception {
        var usuario = new Usuario(7L, "usuario-prueba", "hash-de-prueba", "Nombre", "Prueba",
                "prueba@example.com", null, "es", true);
        String token = new JwtTokenAdapter(encoder).generarToken(usuario);
        var validado = decoder.decode(token);
        assertEquals(List.of("svc-turnos-api", "svc-catalogo-api"), validado.getAudience());
        assertEquals("catalogo:read", validado.getClaimAsString("scope"));
        server.expect(requestTo("http://catalogo.prueba/api/catalogo/profesionales/15/horarios"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/prueba-catalogo").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("0"));
    }

    @Test
    void noConsultaCatalogoSiElTokenEsInvalidoOEstaAusente() throws Exception {
        mockMvc.perform(get("/api/prueba-catalogo")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/prueba-catalogo").header(HttpHeaders.AUTHORIZATION, "Bearer invalido"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class ControladorDePrueba {
        private final ConsultarCatalogoPort catalogo;

        ControladorDePrueba(ConsultarCatalogoPort catalogo) {
            this.catalogo = catalogo;
        }

        @GetMapping("/api/prueba-catalogo")
        String consultar() {
            return Integer.toString(catalogo.consultarHorarios(15L).size());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClienteSimulado {
        private final RestClient.Builder builder = RestClient.builder().baseUrl("http://catalogo.prueba");

        @Bean
        MockRestServiceServer mockServerCatalogo() {
            return MockRestServiceServer.bindTo(builder).build();
        }

        @Bean
        RestClient catalogoRestClient(MockRestServiceServer mockServerCatalogo) {
            return builder.build();
        }
    }
}
