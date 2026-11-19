package com.example.svcturnos.reserva.domain.ports.out;

import com.example.svcturnos.reserva.domain.model.ConfirmacionInicialAceptada;
import com.example.svcturnos.reserva.domain.model.PacienteReserva;

public interface ConfirmarHoldRemotoPort {
    ConfirmacionInicialAceptada confirmar(String holdId, PacienteReserva paciente);
}
