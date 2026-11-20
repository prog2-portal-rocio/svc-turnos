package com.example.svcturnos.reserva.infrastructure.web.controller;

import com.example.svcturnos.reserva.domain.ports.in.EnviarInformacionUseCase;
import com.example.svcturnos.reserva.infrastructure.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservas/holds")
public class InformacionAdicionalController {
    private final EnviarInformacionUseCase servicio;
    public InformacionAdicionalController(EnviarInformacionUseCase servicio) { this.servicio = servicio; }
    @PostMapping("/{procesoId}/telefono")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EnvioInformacionResponse enviar(@PathVariable Long procesoId, @Valid @RequestBody EnviarTelefonoRequest request) {
        return EnvioInformacionResponse.from(servicio.enviar(procesoId, request.phoneNumber()));
    }
}
