package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.ProcesoReserva;

public interface ConfirmarHoldUseCase {
    ProcesoReserva confirmar(Long procesoId);
}
