package com.example.svcturnos.usuario.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.Assert;

import java.io.IOException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    private static final String ISSUER = "svc-turnos";
    private static final String AUDIENCE = "svc-turnos-api";

    @Bean
    public KeyPair jwtKeyPair(
            @Value("${turnos.jwt.private-key-location}") Resource privateKeyResource,
            @Value("${turnos.jwt.public-key-location}") Resource publicKeyResource) throws IOException {

        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;
        try (var privateInput = privateKeyResource.getInputStream();
             var publicInput = publicKeyResource.getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            publicKey = RsaKeyConverters.x509().convert(publicInput);
        }

        Assert.notNull(privateKey, "La clave privada RSA es obligatoria");
        Assert.notNull(publicKey, "La clave publica RSA es obligatoria");
        Assert.isTrue(privateKey.getModulus().bitLength() >= 2048
                        && publicKey.getModulus().bitLength() >= 2048,
                "Las claves RSA deben tener al menos 2048 bits");
        Assert.isTrue(privateKey.getModulus().equals(publicKey.getModulus()),
                "Las claves RSA no corresponden al mismo par");

        return new KeyPair(publicKey, privateKey);
    }

    @Bean
    public JwtEncoder jwtEncoder(KeyPair jwtKeyPair) {
        RSAPublicKey publicKey = (RSAPublicKey) jwtKeyPair.getPublic();
        JwtEncoder encoder = NimbusJwtEncoder
                .withKeyPair(publicKey, (RSAPrivateKey) jwtKeyPair.getPrivate())
                .algorithm(SignatureAlgorithm.RS256)
                .build();

        verificarParDeClaves(encoder, publicKey);
        return encoder;
    }

    @Bean
    public JwtDecoder jwtDecoder(KeyPair jwtKeyPair) {
        return crearDecoder((RSAPublicKey) jwtKeyPair.getPublic());
    }

    private JwtDecoder crearDecoder(RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtAudienceValidator(AUDIENCE),
                new JwtClaimValidator<String>(JwtClaimNames.SUB,
                        subject -> subject != null && !subject.isBlank()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)
        ));
        return decoder;
    }

    private void verificarParDeClaves(JwtEncoder encoder, RSAPublicKey publicKey) {
        // Comprobar el par con Spring, sin persistir ni registrar el JWT de prueba.
        Instant ahora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject("key-validation")
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(60))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        try {
            String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
            crearDecoder(publicKey).decode(token);
        } catch (JwtException e) {
            throw new IllegalArgumentException("El par de claves RSA no permite firmar y verificar JWT", e);
        }
    }
}
