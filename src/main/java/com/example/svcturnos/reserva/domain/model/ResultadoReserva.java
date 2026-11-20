package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Campos contractuales del resultado; el JSON original permanece en la inbox. */
public record ResultadoReserva(TipoResultadoReserva tipo, String groupId, String requestEventId, Long reservationId,
        Long professionalId, LocalDate date, LocalTime startTime, String status, String reason, String message, Instant expiresAt) {
    public ResultadoReserva {
        if (tipo == null) { throw new IllegalArgumentException("Tipo de resultado obligatorio"); }
        obligatorio(groupId); obligatorio(requestEventId);
        switch (tipo) {
            case CONFIRMACION, CANCELACION -> {
                if (reservationId == null || reservationId <= 0 || professionalId == null || professionalId <= 0
                        || date == null || startTime == null) { throw new IllegalArgumentException("Datos de reserva incompletos"); }
                String esperado = tipo == TipoResultadoReserva.CONFIRMACION ? "CONFIRMED" : "CANCELLED";
                if (!esperado.equals(status)) { throw new IllegalArgumentException("Estado externo inconsistente"); }
            }
            case RECHAZO_TELEFONO -> {
                obligatorio(reason); obligatorio(message);
                if (expiresAt == null) { throw new IllegalArgumentException("Vencimiento obligatorio"); }
            }
            case EXPIRACION, INVALIDEZ -> obligatorio(reason);
        }
    }
    private static void obligatorio(String dato) {
        if (dato == null || dato.isBlank()) { throw new IllegalArgumentException("Campo de resultado obligatorio"); }
    }
    public boolean coincideCon(ProcesoReserva proceso) {
        return (professionalId == null || professionalId.equals(proceso.profesionalId()))
                && (date == null || date.equals(proceso.fecha()))
                && (startTime == null || startTime.equals(proceso.hora()));
    }
    public EstadoProcesoReserva estadoDestino() {
        return switch (tipo) {
            case CONFIRMACION -> EstadoProcesoReserva.CONFIRMADA;
            case CANCELACION -> EstadoProcesoReserva.CANCELADA;
            case EXPIRACION -> EstadoProcesoReserva.PROCESO_EXPIRADO;
            case INVALIDEZ -> EstadoProcesoReserva.PROCESO_INVALIDO;
            case RECHAZO_TELEFONO -> EstadoProcesoReserva.ESPERANDO_TELEFONO;
        };
    }
    @Override public String toString() { return "ResultadoReserva[tipo=" + tipo + "]"; }
}
