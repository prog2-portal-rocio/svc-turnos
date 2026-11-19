package com.example.svcturnos.reserva.domain.model;

public record PacienteReserva(Long usuarioId, String patientFirstName, String patientLastName) {
    public PacienteReserva {
        identificadorExterno(usuarioId);
        validarNombre(patientFirstName);
        validarNombre(patientLastName);
    }

    private static void validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank() || nombre.length() < 2 || nombre.length() > 100) {
            throw new IllegalArgumentException("Nombre y apellido del paciente deben tener entre 2 y 100 caracteres");
        }
    }

    public static String identificadorExterno(Long usuarioId) {
        if (usuarioId == null || usuarioId <= 0) { throw new IllegalArgumentException("Usuario inválido"); }
        return "user-" + usuarioId;
    }

    public String externalPatientId() { return identificadorExterno(usuarioId); }
}
