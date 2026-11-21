package com.example.svcturnos.reserva.infrastructure.web.controller;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.in.CancelarReservaUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/reservas")
public class CancelacionReservaController {
    private final CancelarReservaUseCase cancelar;
    public CancelacionReservaController(CancelarReservaUseCase cancelar) { this.cancelar = cancelar; }
    public record Peticion(@Size(max = 500) String reason) { }
    public record Respuesta(String reservationProcessId, Long reservationId, EstadoCancelacion estado,
                            Instant resultadoEn, boolean requiereReconciliacion) {
        static Respuesta from(CancelacionReserva resultado) {
            return new Respuesta(resultado.procesoExternoId(), resultado.reservaId(), resultado.estado(),
                    resultado.resultadoEn(), resultado.requiereReconciliacion());
        }
    }
    @PostMapping("/{reservationProcessId}/cancel")
    public ResponseEntity<Respuesta> cancelar(@PathVariable String reservationProcessId,
            @Valid @RequestBody(required = false) Peticion peticion) {
        var resultado = cancelar.cancelar(new SolicitudCancelacion(reservationProcessId, peticion == null ? null : peticion.reason()));
        return ResponseEntity.status(resultado.estado() == EstadoCancelacion.CANCELADA ? 200 : 202).body(Respuesta.from(resultado));
    }
}
