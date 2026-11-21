package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.CriteriosConsultaReservas;
import com.example.svcturnos.reserva.domain.model.PaginaReservas;

public interface ConsultarReservasRemotasPort {
    PaginaReservas consultar(String externalPatientId, CriteriosConsultaReservas criterios);
}
