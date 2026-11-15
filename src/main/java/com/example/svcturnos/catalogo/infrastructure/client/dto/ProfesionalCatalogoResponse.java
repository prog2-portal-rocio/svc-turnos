package com.example.svcturnos.catalogo.infrastructure.client.dto;

import java.time.Instant;

public record ProfesionalCatalogoResponse(Long id, Long categoryId, String firstName, String lastName,
                                         Boolean enabled, Instant createdAt, Instant updatedAt,
                                         CategoriaResponse category) {

    public record CategoriaResponse(Long id, String name, String description, Boolean enabled,
                                    Instant createdAt, Instant updatedAt) {
    }
}
