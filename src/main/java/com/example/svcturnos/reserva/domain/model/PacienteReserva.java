package com.example.svcturnos.reserva.domain.model;

import com.example.svcturnos.shared.domain.model.NombrePersona;

public record PacienteReserva(Long usuarioId, String patientFirstName, String patientLastName) {
    public PacienteReserva {
        identificadorExterno(usuarioId);
        patientFirstName = new NombrePersona(patientFirstName).valor();
        patientLastName = new NombrePersona(patientLastName).valor();
    }

    public static String identificadorExterno(Long usuarioId) {
        if (usuarioId == null || usuarioId <= 0) { throw new IllegalArgumentException("Usuario inválido"); }
        return "user-" + usuarioId;
    }

    public String externalPatientId() { return identificadorExterno(usuarioId); }
}
