package com.example.svcturnos.catalogo.domain.ports.out;

import com.example.svcturnos.catalogo.domain.model.HorarioSemanal;
import com.example.svcturnos.catalogo.domain.model.Profesional;

import java.util.List;

public interface ConsultarCatalogoPort {

    Profesional consultarProfesional(Long id);

    List<HorarioSemanal> consultarHorarios(Long profesionalId);
}
