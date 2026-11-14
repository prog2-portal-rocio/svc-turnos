package com.example.svcturnos.usuario.infrastructure.security;

import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2idPasswordHasherAdapter implements PasswordHasherPort {

    private final Argon2PasswordEncoder encoder =
            new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String passwordHash) {
        return passwordHash != null && passwordHash.startsWith("$argon2id$")
                && encoder.matches(password, passwordHash);
    }
}
