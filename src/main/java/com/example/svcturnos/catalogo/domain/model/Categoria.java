package com.example.svcturnos.catalogo.domain.model;

import java.time.Instant;

public record Categoria(Long id, String name, String description, boolean enabled,
                        Instant createdAt, Instant updatedAt) {
}
