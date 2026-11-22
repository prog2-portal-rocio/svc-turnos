package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.*;
import java.time.Instant;
import java.util.Optional;

public interface CancelacionReservaPersistencePort {
    Optional<CancelacionReserva> buscar(String procesoExternoId);
    PreparacionCancelacion preparar(Long propietario, ReservaCentral reserva);
    CancelacionReserva confirmar(Long propietario, CancelacionConfirmada resultado);
    CancelacionReserva registrarFallo(Long propietario, String procesoExternoId, EstadoCancelacion estado);
    void confirmarDesdeEvento(ProcesoReserva proceso, Long reservaId, Instant ocurridoEn);
}
