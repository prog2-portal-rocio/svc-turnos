package com.example.svcturnos.usuario.domain.ports.in;

import com.example.svcturnos.usuario.domain.model.Usuario;

public interface AutenticarUsuarioUseCase {

    Usuario autenticar(String login, String password);
}
