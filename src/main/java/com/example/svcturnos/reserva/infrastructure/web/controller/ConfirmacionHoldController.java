package com.example.svcturnos.reserva.infrastructure.web.controller;

import com.example.svcturnos.reserva.domain.ports.in.ConfirmarHoldUseCase;
import com.example.svcturnos.reserva.infrastructure.web.dto.HoldResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservas/holds")
public class ConfirmacionHoldController {
    private final ConfirmarHoldUseCase confirmarHold;

    public ConfirmacionHoldController(ConfirmarHoldUseCase confirmarHold) { this.confirmarHold = confirmarHold; }

    @PostMapping("/{procesoId}/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public HoldResponse confirmar(@PathVariable Long procesoId) {
        return HoldResponse.from(confirmarHold.confirmar(procesoId));
    }
}
