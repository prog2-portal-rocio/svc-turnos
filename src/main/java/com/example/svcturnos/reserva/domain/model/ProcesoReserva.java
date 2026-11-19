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
        if (estado.requiereDatosHold() && !datosHold) {
            throw new IllegalArgumentException("El hold requiere identificadores y vencimiento");
        }
        if (!estado.requiereDatosHold()
                && (holdId != null || procesoExternoId != null || vencimientoHold != null)) {
            throw new IllegalArgumentException("Un proceso sin hold registrado no puede contener datos de hold");
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

    /** Un resultado desconocido no vence localmente; un hold conocido respeta su expiresAt. */
    public boolean impideNuevoHold(Instant ahora) {
        return switch (estado) {
            case HOLD_SOLICITADO, CONFIRMACION_SOLICITADA, CONFIRMACION_INCIERTA, ESPERANDO_TELEFONO -> true;
            case HOLD_REGISTRADO, CONFIRMACION_RECHAZADA -> vencimientoHold.isAfter(ahora);
            default -> false;
        };
    }

    /** Intención durable previa al POST; también representa un resultado remoto desconocido. */
    public ProcesoReserva solicitarHold() {
        return cambiarEstado(EstadoProcesoReserva.HOLD_SOLICITADO);
    }

    public ProcesoReserva rechazarHold() {
        return cambiarEstado(EstadoProcesoReserva.HOLD_RECHAZADO);
    }

    public boolean puedeTransicionarA(EstadoProcesoReserva siguiente) {
        return switch (estado) {
            // Se conserva la transición anterior para procesos y consumidores locales existentes.
            case CREADO -> siguiente == EstadoProcesoReserva.HOLD_SOLICITADO || siguiente == EstadoProcesoReserva.HOLD_REGISTRADO;
            case HOLD_SOLICITADO -> siguiente == EstadoProcesoReserva.HOLD_REGISTRADO || siguiente == EstadoProcesoReserva.HOLD_RECHAZADO;
            case HOLD_REGISTRADO, CONFIRMACION_RECHAZADA -> siguiente == EstadoProcesoReserva.CONFIRMACION_SOLICITADA
                    || siguiente == EstadoProcesoReserva.HOLD_VENCIDO;
            case CONFIRMACION_SOLICITADA -> siguiente == EstadoProcesoReserva.ESPERANDO_TELEFONO
                    || siguiente == EstadoProcesoReserva.CONFIRMACION_INCIERTA || siguiente == EstadoProcesoReserva.CONFIRMACION_RECHAZADA
                    || siguiente == EstadoProcesoReserva.HOLD_VENCIDO || siguiente == EstadoProcesoReserva.HOLD_NO_RECUPERABLE;
            case HOLD_RECHAZADO, CONFIRMACION_INCIERTA, ESPERANDO_TELEFONO, HOLD_VENCIDO, HOLD_NO_RECUPERABLE -> false;
        };
    }

    private ProcesoReserva cambiarEstado(EstadoProcesoReserva siguiente) {
        if (!puedeTransicionarA(siguiente)) { throw new IllegalStateException("Transición de hold no permitida"); }
        return new ProcesoReserva(id, usuarioId, profesionalId, fecha, hora, siguiente, null, null, null,
                creadoEn, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), version);
    }

    /** Registra progreso conocido; no realiza una operación remota ni extiende la vigencia del hold. */
    public ProcesoReserva registrarHold(String hold, String procesoExterno, Instant vencimiento) {
        if (!puedeTransicionarA(EstadoProcesoReserva.HOLD_REGISTRADO)) {
            throw new IllegalStateException("El proceso ya tiene un hold registrado");
        }
        return new ProcesoReserva(id, usuarioId, profesionalId, fecha, hora,
                EstadoProcesoReserva.HOLD_REGISTRADO, hold, procesoExterno, vencimiento,
                creadoEn, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), version);
    }
    public boolean admiteProgreso(ProcesoReserva siguiente) {
        if (!puedeTransicionarA(siguiente.estado())) { return false; }
        if (!estado.requiereDatosHold()) { return true; }
        return holdId.equals(siguiente.holdId()) && procesoExternoId.equals(siguiente.procesoExternoId())
                && !siguiente.vencimientoHold().isAfter(vencimientoHold)
                && (siguiente.estado() == EstadoProcesoReserva.ESPERANDO_TELEFONO || vencimientoHold.equals(siguiente.vencimientoHold()));
    }

    public ProcesoReserva solicitarConfirmacion(Instant ahora) {
        if (!puedeTransicionarA(EstadoProcesoReserva.CONFIRMACION_SOLICITADA)) {
            throw new IllegalStateException("El proceso no admite una nueva confirmación inicial");
        }
        if (!vencimientoHold.isAfter(ahora)) { throw new IllegalStateException("El hold está vencido"); }
        return progresoConHold(EstadoProcesoReserva.CONFIRMACION_SOLICITADA, vencimientoHold, ahora);
    }

    public ProcesoReserva registrarConfirmacion(Instant vencimiento, Instant ahora) {
        if (vencimiento == null) { throw new IllegalArgumentException("Vencimiento obligatorio"); }
        // Un 202 acredita aceptación, incluso si llega tarde. Nunca ampliar el plazo conocido.
        Instant limite = vencimiento.isAfter(vencimientoHold) ? vencimientoHold : vencimiento;
        return progresoConHold(EstadoProcesoReserva.ESPERANDO_TELEFONO,
                limite.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ahora);
    }

    /** La aceptación persistida no implica que todavía se pueda enviar información adicional. */
    public boolean esperaTelefonoVigente(Instant ahora) {
        return estado == EstadoProcesoReserva.ESPERANDO_TELEFONO && vencimientoHold.isAfter(ahora);
    }

    public ProcesoReserva registrarResultadoConfirmacion(EstadoProcesoReserva resultado, Instant ahora) {
        if (resultado == EstadoProcesoReserva.ESPERANDO_TELEFONO || resultado == EstadoProcesoReserva.CONFIRMACION_SOLICITADA) {
            throw new IllegalArgumentException("Utilice la operación específica para iniciar o aceptar la confirmación");
        }
        return progresoConHold(resultado, vencimientoHold, ahora);
    }

    private ProcesoReserva progresoConHold(EstadoProcesoReserva nuevoEstado, Instant vencimiento, Instant ahora) {
        var siguiente = new ProcesoReserva(id, usuarioId, profesionalId, fecha, hora, nuevoEstado, holdId, procesoExternoId,
                vencimiento, creadoEn, ahora, version);
        if (!admiteProgreso(siguiente)) { throw new IllegalStateException("Progreso de confirmación incompatible"); }
        return siguiente;
    }
}
