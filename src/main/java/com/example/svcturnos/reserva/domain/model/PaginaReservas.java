package com.example.svcturnos.reserva.domain.model;

import java.util.List;

public record PaginaReservas(List<ReservaCentral> content, long totalElements, int page, int size) {
    public PaginaReservas {
        content = List.copyOf(content);
        if (page < 0 || size < 1 || content.size() > size || totalElements < 0
                || (!content.isEmpty() && totalElements < (long) page * size + content.size())) {
            throw new IllegalArgumentException("Paginacion de reservas inconsistente");
        }
    }
}
