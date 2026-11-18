package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;

public interface CrearHoldUseCase {
    ProcesoReserva crear(SolicitudHold solicitud);
}
