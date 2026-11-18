package com.example.svcturnos.reserva.infrastructure.web.controller;

import com.example.svcturnos.reserva.domain.model.SolicitudHold;
import com.example.svcturnos.reserva.domain.ports.in.CrearHoldUseCase;
import com.example.svcturnos.reserva.infrastructure.web.dto.HoldResponse;
import com.example.svcturnos.reserva.infrastructure.web.dto.SolicitarHoldRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservas/holds")
public class HoldController {
    private final CrearHoldUseCase crearHold;

    public HoldController(CrearHoldUseCase crearHold) { this.crearHold = crearHold; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse crear(@Valid @RequestBody SolicitarHoldRequest request) {
        return HoldResponse.from(crearHold.crear(new SolicitudHold(request.professionalId(), request.date(), request.startTime())));
    }
}
