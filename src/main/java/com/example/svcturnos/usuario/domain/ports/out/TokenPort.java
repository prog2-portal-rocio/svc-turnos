package com.example.svcturnos.usuario.domain.ports.out;

import com.example.svcturnos.usuario.domain.model.Usuario;

public interface TokenPort {

    String generarToken(Usuario usuario);
}
