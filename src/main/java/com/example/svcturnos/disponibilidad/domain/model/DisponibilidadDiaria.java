package com.example.svcturnos.disponibilidad.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Excluye posiciones con igual fecha e inicio: el contrato v1 no informa fin ni duración ocupada.
 * No detecta solapamientos parciales ni garantiza que el bloque siga libre al solicitar un hold.
 */
public final class DisponibilidadDiaria {
    private DisponibilidadDiaria() { }

    public static List<BloqueHorario> calcular(List<BloqueHorario> candidatos,
                                               List<Ocupacion> ocupaciones, Instant ahora) {
        var bloqueadas = ocupaciones.stream().filter(ocupacion -> ocupacion.vigente(ahora))
                .map(ocupacion -> new Posicion(ocupacion.fecha(), ocupacion.inicio()))
                .collect(Collectors.toSet());
        return candidatos.stream()
                .filter(bloque -> !bloqueadas.contains(new Posicion(bloque.fecha(), bloque.inicio())))
                .toList();
    }

    private record Posicion(LocalDate fecha, LocalTime inicio) { }
}
