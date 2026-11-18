package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.HoldCreado;
import com.example.svcturnos.reserva.domain.model.SolicitudHold;

public interface CrearHoldRemotoPort {
    HoldCreado crear(SolicitudHold solicitud, String externalPatientId);
}
