package com.example.svcturnos.disponibilidad.domain.ports.in;

import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import java.time.LocalDate;
import java.util.List;

public interface ConsultarDisponibilidadUseCase {
    List<BloqueHorario> consultar(Long profesionalId, LocalDate fecha);
}
