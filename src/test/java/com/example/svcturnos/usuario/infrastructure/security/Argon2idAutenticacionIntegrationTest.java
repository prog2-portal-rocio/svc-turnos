package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase;
import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = JwtTestContextInitializer.class)
@Testcontainers
@Transactional
class Argon2idAutenticacionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UsuarioPersistencePort usuarios;

    @Autowired
    private RegistrarUsuarioUseCase registrar;

    @Autowired
    private PasswordHasherPort hasher;

    @Autowired
    private JwtDecoder decoder;

    @ParameterizedTest
    @MethodSource("contrasenasValidas")
    void registroPersisteArgon2idYLoginEmiteUnJwtReal(String password) throws Exception {
        mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registro(password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.login").value("usuario-prueba"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        entityManager.flush();
        entityManager.clear();
        String almacenado = jdbc.queryForObject("SELECT password_hash FROM usuarios", String.class);
        assertNotNull(almacenado);
        assertTrue(almacenado.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertFalse(password.equals(almacenado));
        var usuario = usuarios.buscarPorLogin("usuario-prueba").orElseThrow();
        assertTrue(almacenado.equals(usuario.passwordHash()));
        assertTrue(hasher.matches(password, usuario.passwordHash()));

        String respuesta = mockMvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "usuario-prueba", "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id_token").isString())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var jwt = decoder.decode(json.readTree(respuesta).path("id_token").asString());
        assertEquals("RS256", jwt.getHeaders().get("alg"));
        assertEquals(usuario.id().toString(), jwt.getSubject());
        assertEquals("svc-turnos", jwt.getClaimAsString("iss"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"usuario-prueba", "inexistente"})
    void loginFallidoConserva401YLaRespuestaSinToken(String login) throws Exception {
        registrar.registrar(new RegistrarUsuarioUseCase.DatosRegistro("usuario-prueba", "ClaveCorrecta123",
                "Nombre", "Prueba", "prueba@example.com", null, "es"));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", login, "password", "ClaveIncorrecta"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CREDENCIALES_INVALIDAS"))
                .andExpect(jsonPath("$.detail").value("Credenciales incorrectas"))
                .andExpect(jsonPath("$.id_token").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 101})
    void registroConservaLosLimitesDeLongitud(int longitud) throws Exception {
        mockMvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registro("a".repeat(longitud))))
                .andExpect(status().isBadRequest());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM usuarios", Integer.class));
    }

    private String registro(String password) {
        return json.writeValueAsString(Map.of("login", "usuario-prueba", "password", password,
                "firstName", "Nombre", "lastName", "Prueba", "email", "prueba@example.com", "langKey", "es"));
    }

    private static Stream<String> contrasenasValidas() {
        return Stream.of("ClaveCorrecta123", "Contraseña con ñ y 漢字 🔑", "a".repeat(100), "ñ".repeat(100));
    }
}
