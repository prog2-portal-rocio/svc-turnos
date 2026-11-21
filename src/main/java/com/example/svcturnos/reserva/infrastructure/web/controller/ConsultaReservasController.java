package com.example.svcturnos.reserva.infrastructure.web.controller;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.ConsultarReservasUseCase;
import com.example.svcturnos.reserva.infrastructure.web.dto.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ConsultaReservasController {
    private final ConsultarReservasUseCase servicio;
    public ConsultaReservasController(ConsultarReservasUseCase servicio) { this.servicio = servicio; }

    @GetMapping("/procesos-reserva")
    public List<ProcesoReservaResponse> procesos() {
        return servicio.consultarProcesos().stream().map(ProcesoReservaResponse::from).toList();
    }

    @GetMapping("/procesos-reserva/{id}")
    public ProcesoReservaResponse proceso(@PathVariable Long id) {
        return ProcesoReservaResponse.from(servicio.consultarProceso(id));
    }

    @GetMapping("/reservas")
    public ReservasResponse reservas(
            @RequestParam(defaultValue = "CONFIRMED") EstadoReservaCentral status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        // getParameterValues conserva cada sort=campo,direccion sin separar sus comas.
        var orden = request.getParameterValues("sort");
        return ReservasResponse.from(servicio.consultarReservas(new CriteriosConsultaReservas(
                status, from, to, page, size, orden == null ? List.of() : List.of(orden))));
    }
}
