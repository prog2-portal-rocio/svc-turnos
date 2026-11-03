package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenAdapterTest {

    private final JwtConfig config = new JwtConfig();
    private final SecretKey clave = generarClave();
    private final JwtEncoder encoder = config.jwtEncoder(clave);
    private final JwtDecoder decoder = config.jwtDecoder(clave);
    private final JwtTokenAdapter adapter = new JwtTokenAdapter(encoder);

    private static SecretKey generarClave() {
        try {
            KeyGenerator generador =
                    KeyGenerator.getInstance("HmacSHA256");
            generador.init(256);
            return generador.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Usuario crearUsuario() {
        return new Usuario(
                1L, "rocio", "hash-de-prueba",
                "Rocio", "Portal", "rocio@example.com",
                null, "es", true
        );
    }

    private String firmarToken(
            String audiencia,
            Instant vencimiento
    ) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("svc-turnos")
                .subject("1")
                .audience(List.of(audiencia))
                .issuedAt(vencimiento.minus(1, ChronoUnit.HOURS))
                .expiresAt(vencimiento)
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .build();

        return encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }

    @Test
    void debeGenerarYValidarUnTokenReal() {
        String token = adapter.generarToken(crearUsuario());

        Jwt jwt = decoder.decode(token);

        assertEquals("1", jwt.getSubject());
        assertEquals("svc-turnos", jwt.getClaimAsString("iss"));
        assertEquals("rocio", jwt.getClaimAsString("login"));
        assertTrue(jwt.getAudience().contains("svc-turnos-api"));
        assertTrue(jwt.getExpiresAt().isAfter(Instant.now()));
        assertFalse(jwt.getClaims().containsKey("passwordHash"));
    }

    @Test
    void debeRechazarTokenFirmadoConOtraClave() {
        String token = adapter.generarToken(crearUsuario());

        JwtDecoder otroDecoder =
                config.jwtDecoder(generarClave());

        assertThrows(
                JwtException.class,
                () -> otroDecoder.decode(token)
        );
    }

    @Test
    void debeRechazarTokenVencido() {
        String token = firmarToken(
                "svc-turnos-api",
                Instant.now().minus(2, ChronoUnit.HOURS)
        );

        assertThrows(
                JwtException.class,
                () -> decoder.decode(token)
        );
    }

    @Test
    void debeRechazarTokenConAudienciaIncorrecta() {
        String token = firmarToken(
                "otra-aplicacion",
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        assertThrows(
                JwtException.class,
                () -> decoder.decode(token)
        );
    }
}
