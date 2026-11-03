package com.example.svcturnos.usuario.application.service;

import com.example.svcturnos.usuario.application.exception.CredencialesInvalidasException;
import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.in.AutenticarUsuarioUseCase;
import com.example.svcturnos.usuario.domain.ports.out.PasswordHasherPort;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AutenticarUsuarioService implements AutenticarUsuarioUseCase {

    private final UsuarioPersistencePort usuarios;
    private final PasswordHasherPort passwordHasher;

    public AutenticarUsuarioService(
            UsuarioPersistencePort usuarios,
            PasswordHasherPort passwordHasher
    ) {
        this.usuarios = usuarios;
        this.passwordHasher = passwordHasher;
    }

    @Override
    @Transactional(readOnly = true)
    public Usuario autenticar(String login, String password) {

        if (login == null || login.isBlank()
                || password == null || password.isBlank()) {
            throw new CredencialesInvalidasException();
        }

        String loginNormalizado =
                login.trim().toLowerCase(Locale.ROOT);

        Usuario usuario = usuarios.buscarPorLogin(loginNormalizado)
                .orElseThrow(CredencialesInvalidasException::new);

        if (!usuario.activated()
                || !passwordHasher.matches(password, usuario.passwordHash())) {
            throw new CredencialesInvalidasException();
        }

        return usuario;
    }
}
