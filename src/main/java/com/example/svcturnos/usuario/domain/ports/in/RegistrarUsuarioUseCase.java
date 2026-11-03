package com.example.svcturnos.usuario.domain.ports.in;

import com.example.svcturnos.usuario.domain.model.Usuario;

public interface RegistrarUsuarioUseCase {

    Usuario registrar(DatosRegistro datos);

    record DatosRegistro(
            String login,
            String password,
            String firstName,
            String lastName,
            String email,
            String imageUrl,
            String langKey
    ) {
    }
}
