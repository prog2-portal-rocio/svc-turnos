package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.PacienteReserva;

public interface UsuarioAutenticadoPort {
    Long obtenerUsuarioId();
    PacienteReserva obtenerPaciente();
}
