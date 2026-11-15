package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.ProcesoReserva;
import java.util.List;
import java.util.Optional;

public interface ProcesoReservaPersistencePort {
    ProcesoReserva guardar(ProcesoReserva proceso);
    Optional<ProcesoReserva> buscarPorId(Long id);
    List<ProcesoReserva> buscarPorUsuario(Long usuarioId);
}
