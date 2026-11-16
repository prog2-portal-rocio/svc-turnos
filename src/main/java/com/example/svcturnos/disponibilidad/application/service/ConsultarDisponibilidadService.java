package com.example.svcturnos.disponibilidad.application.service;

import com.example.svcturnos.catalogo.domain.exception.ConsultaCatalogoException;
import com.example.svcturnos.catalogo.domain.model.Profesional;
import com.example.svcturnos.catalogo.domain.ports.out.ConsultarCatalogoPort;
import com.example.svcturnos.disponibilidad.domain.exception.ConsultaOcupacionesException;
import com.example.svcturnos.disponibilidad.domain.model.AgendaDiaria;
import com.example.svcturnos.disponibilidad.domain.model.BloqueHorario;
import com.example.svcturnos.disponibilidad.domain.model.DisponibilidadDiaria;
import com.example.svcturnos.disponibilidad.domain.ports.in.ConsultarDisponibilidadUseCase;
import com.example.svcturnos.disponibilidad.domain.ports.out.ConsultarOcupacionesPort;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class ConsultarDisponibilidadService implements ConsultarDisponibilidadUseCase {
    private final ConsultarCatalogoPort catalogo;
    private final ConsultarOcupacionesPort ocupaciones;
    private final Clock clock;

    public ConsultarDisponibilidadService(ConsultarCatalogoPort catalogo, ConsultarOcupacionesPort ocupaciones,
                                          Clock clock) {
        this.catalogo = catalogo;
        this.ocupaciones = ocupaciones;
        this.clock = clock;
    }

    @Override
    public List<BloqueHorario> consultar(Long profesionalId, LocalDate fecha) {
        if (profesionalId == null || profesionalId <= 0 || fecha == null) {
            throw new IllegalArgumentException("La consulta requiere profesional positivo y fecha");
        }
        var profesional = catalogo.consultarProfesional(profesionalId);
        validarProfesional(profesionalId, profesional);
        if (!profesional.enabled() || !profesional.category().enabled()) {
            return List.of();
        }
        var candidatos = generarCandidatos(profesionalId, fecha);
        if (candidatos.isEmpty()) {
            return List.of();
        }
        var posiciones = ocupaciones.consultarOcupaciones(profesionalId, fecha);
        if (posiciones == null || posiciones.stream().anyMatch(posicion -> posicion == null
                || !fecha.equals(posicion.fecha()))) {
            throw new ConsultaOcupacionesException(ConsultaOcupacionesException.Motivo.RESPUESTA_INVALIDA);
        }
        return DisponibilidadDiaria.calcular(candidatos, posiciones, clock.instant());
    }

    private List<BloqueHorario> generarCandidatos(Long profesionalId, LocalDate fecha) {
        var horarios = catalogo.consultarHorarios(profesionalId);
        try {
            return AgendaDiaria.generar(profesionalId, fecha, horarios);
        } catch (IllegalArgumentException _) {
            throw new ConsultaCatalogoException(ConsultaCatalogoException.Motivo.RESPUESTA_INVALIDA);
        }
    }

    private void validarProfesional(Long profesionalId, Profesional profesional) {
        if (profesional == null || !profesionalId.equals(profesional.id()) || profesional.category() == null
                || profesional.categoryId() == null || !profesional.categoryId().equals(profesional.category().id())) {
            throw new ConsultaCatalogoException(ConsultaCatalogoException.Motivo.RESPUESTA_INVALIDA);
        }
    }
}
