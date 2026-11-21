package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.*;

public interface CancelarReservaRemotaPort {
    CancelacionConfirmada cancelar(SolicitudCancelacion solicitud, Long reservaEsperada);
}
