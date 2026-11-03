package com.example.svcturnos.usuario.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AutenticacionRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
