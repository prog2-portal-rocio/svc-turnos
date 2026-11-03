package com.example.svcturnos.usuario.domain.model;

public record Usuario(
        Long id,
        String login,
        String passwordHash,
        String firstName,
        String lastName,
        String email,
        String imageUrl,
        String langKey,
        boolean activated
) {
}
