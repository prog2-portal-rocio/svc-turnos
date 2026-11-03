package com.example.svcturnos.usuario.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AutenticacionResponse(
        @JsonProperty("id_token") String idToken
) {
}
