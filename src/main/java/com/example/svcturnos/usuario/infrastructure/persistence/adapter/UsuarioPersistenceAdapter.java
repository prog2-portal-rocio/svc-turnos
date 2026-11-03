package com.example.svcturnos.usuario.infrastructure.persistence.adapter;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.domain.ports.out.UsuarioPersistencePort;
import com.example.svcturnos.usuario.infrastructure.persistence.mapper.UsuarioMapper;
import com.example.svcturnos.usuario.infrastructure.persistence.repository.UsuarioJpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UsuarioPersistenceAdapter implements UsuarioPersistencePort {

    private final UsuarioJpaRepository repository;
    private final UsuarioMapper mapper;

    public UsuarioPersistenceAdapter(UsuarioJpaRepository repository) {
        this.repository = repository;
        this.mapper = new UsuarioMapper();
    }

    @Override
    public Usuario guardar(Usuario usuario) {
        var entity = mapper.toEntity(usuario);
        var guardado = repository.save(entity);
        return mapper.toDomain(guardado);
    }

    @Override
    public Optional<Usuario> buscarPorLogin(String login) {
        return repository.findByLoginIgnoreCase(login)
                .map(mapper::toDomain);
    }

    @Override
    public boolean existeLogin(String login) {
        return repository.existsByLoginIgnoreCase(login);
    }

    @Override
    public boolean existeEmail(String email) {
        return repository.existsByEmailIgnoreCase(email);
    }
}
