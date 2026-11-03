package com.example.svcturnos.usuario.domain.ports.out;

import com.example.svcturnos.usuario.domain.model.Usuario;
import java.util.Optional;

public interface UsuarioPersistencePort {

    Usuario guardar(Usuario usuario);

    Optional<Usuario> buscarPorLogin(String login);

    boolean existeLogin(String login);

    boolean existeEmail(String email);
}
