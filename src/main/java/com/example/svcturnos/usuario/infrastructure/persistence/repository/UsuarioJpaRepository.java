package com.example.svcturnos.usuario.infrastructure.persistence.repository;

import com.example.svcturnos.usuario.infrastructure.persistence.entity.UsuarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UsuarioJpaRepository
        extends JpaRepository<UsuarioEntity, Long> {

    Optional<UsuarioEntity> findByLoginIgnoreCase(String login);

    boolean existsByLoginIgnoreCase(String login);

    boolean existsByEmailIgnoreCase(String email);
}
