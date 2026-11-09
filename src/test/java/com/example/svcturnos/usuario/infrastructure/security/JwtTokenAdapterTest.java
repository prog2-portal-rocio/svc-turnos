package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class JwtTokenAdapterTest {

    private static final KeyPair CLAVES = JwtTestContextInitializer.claves();
    private final JwtConfig config = new JwtConfig();
    private final JwtEncoder encoder = config.jwtEncoder(CLAVES);
    private final JwtDecoder decoder = config.jwtDecoder(CLAVES);
    private final JwtTokenAdapter adapter = new JwtTokenAdapter(encoder);

    @Test
    void debeGenerarYValidarUnTokenRealRs256ConLosClaimsExistentes() {
        Usuario usuario = new Usuario(
                1L, "rocio", "hash-de-prueba", "Rocio", "Portal",
                "rocio@example.com", null, "es", true);
        String token = adapter.generarToken(usuario);
        Jwt jwt = decoder.decode(token);

        assertEquals("RS256", jwt.getHeaders().get("alg"));
        assertEquals("1", jwt.getSubject());
        assertEquals("svc-turnos", jwt.getClaimAsString("iss"));
        assertEquals("rocio", jwt.getClaimAsString("login"));
        assertEquals(List.of("svc-turnos-api", "svc-catalogo-api"), jwt.getAudience());
        assertEquals(Duration.ofHours(2), Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
        assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));
        assertFalse(jwt.getClaims().containsKey("passwordHash"));
        assertEquals("catalogo:read", jwt.getClaimAsString("scope"));
    }

    @Test
    void noEmiteTokensParaUsuariosInactivos() {
        JwtEncoder encoderSinUsar = mock(JwtEncoder.class);
        JwtTokenAdapter emisor = new JwtTokenAdapter(encoderSinUsar);
        Usuario inactivo = new Usuario(1L, "rocio", "hash", "Rocio", "Portal",
                "rocio@example.com", null, "es", false);

        assertThrows(IllegalArgumentException.class, () -> emisor.generarToken(inactivo));
        verifyNoInteractions(encoderSinUsar);
    }

    @Test
    void cumpleContratoDeCatalogoSinConcederPermisoDeSincronizacion() {
        Usuario usuario = new Usuario(1L, "rocio", "hash", "Rocio", "Portal",
                "rocio@example.com", null, "es", true);
        String token = adapter.generarToken(usuario);

        // Verificar el contrato externo con una publica RSA, sin depender del otro repositorio.
        NimbusJwtDecoder catalogoDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) CLAVES.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        catalogoDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator("svc-turnos"),
                new JwtAudienceValidator("svc-catalogo-api"),
                new JwtClaimValidator<String>(JwtClaimNames.SUB,
                        subject -> subject != null && !subject.isBlank()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)
        ));

        Jwt validado = catalogoDecoder.decode(token);
        var authentication = new JwtAuthenticationConverter().convert(validado);

        assertNotNull(authentication);
        assertEquals("catalogo:read", validado.getClaimAsString("scope"));
        assertTrue(authentication.getAuthorities()
                .contains(new SimpleGrantedAuthority("SCOPE_catalogo:read")));
        assertFalse(authentication.getAuthorities()
                .contains(new SimpleGrantedAuthority("SCOPE_catalogo:sync:read")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensInvalidos")
    void debeRechazarTokensInvalidos(String descripcion, String token) {
        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    private static JWTClaimsSet.Builder claimsValidos() {
        Instant ahora = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("svc-turnos")
                .subject("1")
                .audience("svc-turnos-api")
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plusSeconds(300)));
    }

    private static String firmar(JWTClaimsSet claims) throws JOSEException {
        return firmar(claims, CLAVES, JWSAlgorithm.RS256);
    }

    private static String firmar(JWTClaimsSet claims, KeyPair claves, JWSAlgorithm algoritmo)
            throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(algoritmo), claims);
        jwt.sign(new RSASSASigner(claves.getPrivate()));
        return jwt.serialize();
    }

    private static Stream<Arguments> tokensInvalidos() throws JOSEException {
        byte[] secreto = new byte[32];
        new SecureRandom().nextBytes(secreto);
        SignedJWT hs256 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsValidos().build());
        hs256.sign(new MACSigner(secreto));

        return Stream.of(
                Arguments.of("Alterado", alterar(firmar(claimsValidos().build()))),
                Arguments.of("Otra clave RSA", firmar(claimsValidos().build(),
                        JwtTestContextInitializer.generarClaves(2048), JWSAlgorithm.RS256)),
                Arguments.of("HS256", hs256.serialize()),
                Arguments.of("RS512", firmar(claimsValidos().build(), CLAVES, JWSAlgorithm.RS512)),
                Arguments.of("Vencido", firmar(claimsValidos()
                        .expirationTime(Date.from(Instant.now().minusSeconds(300))).build())),
                Arguments.of("Sin expiracion", firmar(claimsValidos().expirationTime(null).build())),
                Arguments.of("Emisor incorrecto", firmar(claimsValidos().issuer("otro-servicio").build())),
                Arguments.of("Sin emisor", firmar(claimsValidos().issuer(null).build())),
                Arguments.of("Audiencia incorrecta", firmar(claimsValidos().audience("svc-catalogo-api").build())),
                Arguments.of("Sin audiencia", firmar(claimsValidos().audience((String) null).build())),
                Arguments.of("Sujeto ausente", firmar(claimsValidos().subject(null).build())),
                Arguments.of("Sujeto vacio", firmar(claimsValidos().subject("").build())),
                Arguments.of("Sujeto en blanco", firmar(claimsValidos().subject("  ").build()))
        );
    }

    private static String alterar(String token) {
        String[] partes = token.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"1\"", "\"sub\":\"2\"");
        partes[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        return String.join(".", partes);
    }
}
