package com.example.svcturnos.disponibilidad.domain.model;

import com.example.svcturnos.catalogo.domain.model.HorarioSemanal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public final class AgendaDiaria {
    private AgendaDiaria() { }

    public static List<BloqueHorario> generar(Long profesionalId, LocalDate fecha, List<HorarioSemanal> horarios) {
        if (profesionalId == null || profesionalId <= 0 || fecha == null || horarios == null) {
            throw new IllegalArgumentException("La agenda requiere profesional, fecha y horarios");
        }
        var bloques = new TreeSet<>(Comparator.comparing(BloqueHorario::inicio).thenComparing(BloqueHorario::fin));
        for (var horario : horarios) {
            validarIdentidad(profesionalId, horario);
            if (horario.enabled() && horario.dayOfWeek() == fecha.getDayOfWeek()) {
                agregarBloques(fecha, horario, bloques);
            }
        }
        return List.copyOf(bloques);
    }

    private static void validarIdentidad(Long profesionalId, HorarioSemanal horario) {
        if (horario == null || !profesionalId.equals(horario.professionalId()) || horario.dayOfWeek() == null) {
            throw new IllegalArgumentException("Horario ajeno o incompleto");
        }
    }

    private static void agregarBloques(LocalDate fecha, HorarioSemanal horario, TreeSet<BloqueHorario> bloques) {
        if (horario.startTime() == null || horario.endTime() == null
                || !horario.startTime().isBefore(horario.endTime())
                || horario.slotDurationMinutes() == null || horario.slotDurationMinutes() <= 0) {
            throw new IllegalArgumentException("Intervalo o duración de agenda inválidos");
        }
        var duracion = Duration.ofMinutes(horario.slotDurationMinutes());
        var inicio = horario.startTime();
        while (Duration.between(inicio, horario.endTime()).compareTo(duracion) >= 0) {
            var fin = inicio.plus(duracion);
            bloques.add(new BloqueHorario(fecha, inicio, fin));
            inicio = fin;
        }
    }
}
