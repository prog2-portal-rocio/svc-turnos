package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.TokenPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Component
public class JwtTokenAdapter implements TokenPort {

    private final JwtEncoder encoder;

    public JwtTokenAdapter(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String generarToken(Usuario usuario) {
        Objects.requireNonNull(usuario, "Usuario obligatorio");
        Objects.requireNonNull(
                usuario.id(),
                "El usuario debe estar guardado"
        );
        if (!usuario.activated()) {
            throw new IllegalArgumentException("El usuario debe estar activo para emitir un token");
        }

        Instant ahora = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtContract.ISSUER)
                .subject(usuario.id().toString())
                .audience(JwtContract.USER_AUDIENCES)
                .issuedAt(ahora)
                .expiresAt(ahora.plus(2, ChronoUnit.HOURS))
                .claim("login", usuario.login())
                // Politica fija para usuarios activos; los permisos no provienen del cliente.
                .claim("scope", JwtContract.USER_SCOPE)
                .build();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .build();

        return encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
