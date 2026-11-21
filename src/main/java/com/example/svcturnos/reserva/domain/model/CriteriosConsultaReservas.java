package com.example.svcturnos.reserva.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record CriteriosConsultaReservas(EstadoReservaCentral status, LocalDate from, LocalDate to,
                                       int page, int size, List<String> sort) {
    private static final Set<String> CAMPOS_ORDEN = Set.of("appointmentDate", "startTime", "createdAt", "reservationId");

    public CriteriosConsultaReservas {
        if (status == null) { status = EstadoReservaCentral.CONFIRMED; }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("to no puede ser anterior a from");
        }
        // Limite propio de la API de Turnos para acotar cada consulta externa.
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page debe ser no negativo y size estar entre 1 y 100");
        }
        sort = sort == null ? List.of() : List.copyOf(sort);
        for (String orden : sort) { validarOrden(orden); }
    }

    private static void validarOrden(String orden) {
        String[] partes = orden.split(",", -1);
        if (partes.length != 2 || !CAMPOS_ORDEN.contains(partes[0])
                || !("asc".equals(partes[1]) || "desc".equals(partes[1]))) {
            throw new IllegalArgumentException("sort requiere un campo permitido y direccion asc o desc");
        }
    }
}
