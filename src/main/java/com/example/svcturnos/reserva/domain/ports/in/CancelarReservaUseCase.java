package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.*;

public interface CancelarReservaUseCase { CancelacionReserva cancelar(SolicitudCancelacion solicitud); }
