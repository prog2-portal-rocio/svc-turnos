package com.example.svcturnos.catalogo.domain.model;

import java.time.Instant;

public record Profesional(Long id, Long categoryId, String firstName, String lastName, boolean enabled,
                          Instant createdAt, Instant updatedAt, Categoria category) {
}
