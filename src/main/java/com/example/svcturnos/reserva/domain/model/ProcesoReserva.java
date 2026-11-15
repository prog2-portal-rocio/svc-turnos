package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ProcesoReserva(
        Long id, Long usuarioId, Long profesionalId, LocalDate fecha, LocalTime hora,
        EstadoProcesoReserva estado, String holdId, String procesoExternoId, Instant vencimientoHold,
        Instant creadoEn, Instant actualizadoEn, Long version) {

    public ProcesoReserva {
        positivo(usuarioId);
        positivo(profesionalId);
        if (fecha == null || hora == null || estado == null || creadoEn == null || actualizadoEn == null) {
            throw new IllegalArgumentException("El proceso requiere fecha, hora, estado y auditoría");
        }
        // Precisión de microsegundos para conservar igualdad al recuperar los datos persistidos.
        hora = hora.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        creadoEn = creadoEn.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        actualizadoEn = actualizadoEn.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (vencimientoHold != null) {
            vencimientoHold = vencimientoHold.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
        if (actualizadoEn.isBefore(creadoEn) || (version != null && version < 0)
                || (id != null && id <= 0) || ((id == null) != (version == null))) {
            throw new IllegalArgumentException("Identidad, versión o auditoría inválidas");
        }
        boolean datosHold = holdId != null && !holdId.isBlank()
                && procesoExternoId != null && !procesoExternoId.isBlank() && vencimientoHold != null;
        if (estado == EstadoProcesoReserva.HOLD_REGISTRADO && !datosHold) {
            throw new IllegalArgumentException("El hold requiere identificadores y vencimiento");
        }
        if (estado == EstadoProcesoReserva.CREADO
                && (holdId != null || procesoExternoId != null || vencimientoHold != null)) {
            throw new IllegalArgumentException("Un proceso creado no tiene hold");
        }
    }

    private static void positivo(Long valor) {
        if (valor == null || valor <= 0) {
            throw new IllegalArgumentException("Los identificadores deben ser positivos");
        }
    }

    public static ProcesoReserva crear(Long usuarioId, Long profesionalId, LocalDate fecha, LocalTime hora) {
        Instant ahora = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new ProcesoReserva(null, usuarioId, profesionalId, fecha, hora,
                EstadoProcesoReserva.CREADO, null, null, null, ahora, ahora, null);
    }

    /** Registra progreso conocido; no realiza una operación remota ni extiende la vigencia del hold. */
    public ProcesoReserva registrarHold(String hold, String procesoExterno, Instant vencimiento) {
        if (estado != EstadoProcesoReserva.CREADO) {
            throw new IllegalStateException("El proceso ya tiene un hold registrado");
        }
        return new ProcesoReserva(id, usuarioId, profesionalId, fecha, hora,
                EstadoProcesoReserva.HOLD_REGISTRADO, hold, procesoExterno, vencimiento,
                creadoEn, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), version);
    }
}
