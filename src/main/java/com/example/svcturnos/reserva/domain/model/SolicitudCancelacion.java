package com.example.svcturnos.reserva.domain.model;

public record SolicitudCancelacion(String reservationProcessId, String reason) {
    public SolicitudCancelacion {
        if (reservationProcessId == null || reservationProcessId.isBlank() || reservationProcessId.length() > 255
                || (reason != null && reason.length() > 500)) {
            throw new IllegalArgumentException("Proceso o motivo de cancelacion invalidos");
        }
    }
    @Override public String toString() { return "SolicitudCancelacion[protegida]"; }
}
