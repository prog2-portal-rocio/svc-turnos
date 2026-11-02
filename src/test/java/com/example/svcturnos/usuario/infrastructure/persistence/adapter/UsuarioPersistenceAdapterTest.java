package com.example.svcturnos.usuario.infrastructure.persistence.adapter;

import com.example.svcturnos.usuario.domain.model.Usuario;
import com.example.svcturnos.usuario.infrastructure.persistence.entity.UsuarioEntity;
import com.example.svcturnos.usuario.infrastructure.persistence.repository.UsuarioJpaRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UsuarioPersistenceAdapterTest {

    @Test
    void debeGuardarUsuarioYDevolverModeloDeDominio() {
        UsuarioJpaRepository repository = mock(UsuarioJpaRepository.class);
        UsuarioPersistenceAdapter adapter =
                new UsuarioPersistenceAdapter(repository);

        Usuario usuario = new Usuario(
                null,
                "rocio",
                "hash-de-prueba",
                "Rocio",
                "Portal",
                "rocio@example.com",
                null,
                "es",
                true
        );

        when(repository.save(any(UsuarioEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Usuario resultado = adapter.guardar(usuario);

        assertEquals(usuario, resultado);
        verify(repository).save(any(UsuarioEntity.class));
    }
}
