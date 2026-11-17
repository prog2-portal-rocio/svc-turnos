package com.example.svcturnos.disponibilidad.domain.ports.out;

import com.example.svcturnos.disponibilidad.domain.model.Ocupacion;
import java.time.LocalDate;
import java.util.List;

public interface ConsultarOcupacionesPort {
    List<Ocupacion> consultarOcupaciones(Long profesionalId, LocalDate fecha);
}
