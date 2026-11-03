package com.example.svcturnos.usuario.infrastructure.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegistroRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9_.@-]+$")
        String login,

        @NotBlank
        @Size(min = 4, max = 100)
        String password,

        @NotBlank
        @Size(max = 50)
        String firstName,

        @NotBlank
        @Size(max = 50)
        String lastName,

        @NotBlank
        @Email
        @Size(min = 5, max = 254)
        String email,

        @Size(max = 256)
        String imageUrl,

        @Size(min = 2, max = 10)
        String langKey
) {
}
