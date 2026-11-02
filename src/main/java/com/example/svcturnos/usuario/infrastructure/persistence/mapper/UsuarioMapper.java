package com.example.svcturnos.usuario.infrastructure.persistence.mapper;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.infrastructure.persistence.entity.UsuarioEntity;

public class UsuarioMapper {

    public UsuarioEntity toEntity(Usuario usuario) {
        return UsuarioEntity.fromDomain(usuario);
    }

    public Usuario toDomain(UsuarioEntity entity) {
        return entity.toDomain();
    }
}
