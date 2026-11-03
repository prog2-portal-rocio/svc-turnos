package com.example.svcturnos.usuario.infrastructure.web.dto;

public record RegistroResponse(
        Long id,
        String login,
        String firstName,
        String lastName,
        String email,
        String imageUrl,
        String langKey,
        boolean activated
) {
}
