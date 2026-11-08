package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.TokenPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

        Instant ahora = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("svc-turnos")
                .subject(usuario.id().toString())
                .audience(List.of("svc-turnos-api"))
                .issuedAt(ahora)
                .expiresAt(ahora.plus(2, ChronoUnit.HOURS))
                .claim("login", usuario.login())
                .build();

        JwsHeader header = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .build();

        return encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
