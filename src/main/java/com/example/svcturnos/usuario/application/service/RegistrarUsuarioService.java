package com.example.svcturnos.usuario.application.service;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.RegistrarUsuarioUseCase;
import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.svcturnos.usuario.application.exception.UsuarioDuplicadoException;

import java.util.Locale;
import java.util.Objects;

@Service
public class RegistrarUsuarioService implements RegistrarUsuarioUseCase {

    private final UsuarioPersistencePort usuarioPersistencePort;
    private final PasswordHasherPort passwordHasherPort;

    public RegistrarUsuarioService(
            UsuarioPersistencePort usuarioPersistencePort,
            PasswordHasherPort passwordHasherPort
    ) {
        this.usuarioPersistencePort = usuarioPersistencePort;
        this.passwordHasherPort = passwordHasherPort;
    }

    @Override
    @Transactional
    public Usuario registrar(DatosRegistro datos) {
        Objects.requireNonNull(datos, "Los datos de registro son obligatorios");

        if (datos.login() == null || datos.login().isBlank()
                || datos.email() == null || datos.email().isBlank()
                || datos.password() == null || datos.password().isBlank()
                || datos.firstName() == null || datos.firstName().isBlank()
                || datos.lastName() == null || datos.lastName().isBlank()) {
            throw new IllegalArgumentException("Faltan datos obligatorios");
        }

        String login = datos.login().trim().toLowerCase(Locale.ROOT);
        String email = datos.email().trim().toLowerCase(Locale.ROOT);

        if (datos.password().length() < 4 || datos.password().length() > 100) {
            throw new IllegalArgumentException(
                    "La contraseña debe tener entre 4 y 100 caracteres"
            );
        }

        if (!email.matches("^[^\\s@]++@[^\\s@.]++(?:\\.[^\\s@.]++)+$")) {
            throw new IllegalArgumentException(
                    "El formato del email no es válido"
            );
        }

        if (usuarioPersistencePort.existeLogin(login)) {
            throw new UsuarioDuplicadoException(
                    "El login ya está registrado"
            );
        }

        if (usuarioPersistencePort.existeEmail(email)) {
            throw new UsuarioDuplicadoException(
                    "El email ya está registrado"
            );
        }

        String passwordHash = passwordHasherPort.hash(datos.password());

        String langKey = datos.langKey() == null || datos.langKey().isBlank()
                ? "es"
                : datos.langKey().trim();

        Usuario usuario = new Usuario(
                null,
                login,
                passwordHash,
                datos.firstName().trim(),
                datos.lastName().trim(),
                email,
                datos.imageUrl(),
                langKey,
                true
        );

        return usuarioPersistencePort.guardar(usuario);
    }
}
