package com.example.svcturnos.reserva.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record EnviarTelefonoRequest(@NotBlank String phoneNumber) {
    @Override public String toString() { return "EnviarTelefonoRequest[protegido]"; }
}
