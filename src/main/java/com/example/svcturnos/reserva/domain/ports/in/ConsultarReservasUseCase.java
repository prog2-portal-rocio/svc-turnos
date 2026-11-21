package com.example.svcturnos.reserva.domain.ports.in;

import com.example.svcturnos.reserva.domain.model.*;
import java.util.List;

public interface ConsultarReservasUseCase {
    List<ProcesoReserva> consultarProcesos();
    ProcesoReserva consultarProceso(Long procesoId);
    PaginaReservas consultarReservas(CriteriosConsultaReservas criterios);
}
