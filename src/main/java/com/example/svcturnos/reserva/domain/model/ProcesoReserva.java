package com.example.svcturnos.reserva.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record ProcesoReserva(
        Long id, Long usuarioId, Long profesionalId, LocalDate fecha, LocalTime hora,
        EstadoProcesoReserva estado, String holdId, String procesoExternoId, Instant vencimientoHold,
        Instant creadoEn, Instant actualizadoEn, Long version, Long reservaId, Instant resultadoOcurridoEn) {

    public ProcesoReserva(Long id, Long usuarioId, Long profesionalId, LocalDate fecha, LocalTime hora,
            EstadoProcesoReserva estado, String holdId, String procesoExternoId, Instant vencimientoHold,
            Instant creadoEn, Instant actualizadoEn, Long version) {
        this(id, usuarioId, profesionalId, fecha, hora, estado, holdId, procesoExternoId, vencimientoHold,
                creadoEn, actualizadoEn, version, null, null);
    }

    public ProcesoReserva {
        validarDatosResultado(estado, reservaId, resultadoOcurridoEn);
        if (resultadoOcurridoEn != null) { resultadoOcurridoEn = resultadoOcurridoEn.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
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
        validarIdentidadYAuditoria(id, version, creadoEn, actualizadoEn);
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

    private static void validarIdentidadYAuditoria(Long id, Long version, Instant creadoEn, Instant actualizadoEn) {
        if (actualizadoEn.isBefore(creadoEn) || (version != null && version < 0)
                || (id != null && id <= 0) || ((id == null) != (version == null))) {
            throw new IllegalArgumentException("Identidad, versión o auditoría inválidas");
        }
    }

    private static void validarDatosResultado(EstadoProcesoReserva estado, Long reservaId, Instant resultadoOcurridoEn) {
        boolean reservaDefinitiva = estado == EstadoProcesoReserva.CONFIRMADA || estado == EstadoProcesoReserva.CANCELADA;
        if (reservaDefinitiva != (reservaId != null) || (reservaId != null && reservaId <= 0)) {
            throw new IllegalArgumentException("Identificador de reserva inconsistente");
        }
        if ((reservaDefinitiva || estado == EstadoProcesoReserva.PROCESO_EXPIRADO || estado == EstadoProcesoReserva.PROCESO_INVALIDO)
                && resultadoOcurridoEn == null) { throw new IllegalArgumentException("El resultado requiere fecha de ocurrencia"); }
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
            case HOLD_SOLICITADO, CONFIRMACION_SOLICITADA, CONFIRMACION_INCIERTA, ESPERANDO_TELEFONO, CONFIRMADA, PROCESO_INVALIDO -> true;
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
        if (siguiente == EstadoProcesoReserva.CANCELADA && estado == EstadoProcesoReserva.CONFIRMADA) { return true; }
        if (!estado.resultadoDefinitivo() && estado.requiereDatosHold() && esCierreKafka(siguiente)) { return true; }
        return switch (estado) {
            // Se conserva la transición anterior para procesos y consumidores locales existentes.
            case CREADO -> siguiente == EstadoProcesoReserva.HOLD_SOLICITADO || siguiente == EstadoProcesoReserva.HOLD_REGISTRADO;
            case HOLD_SOLICITADO -> siguiente == EstadoProcesoReserva.HOLD_REGISTRADO || siguiente == EstadoProcesoReserva.HOLD_RECHAZADO;
            case HOLD_REGISTRADO, CONFIRMACION_RECHAZADA -> siguiente == EstadoProcesoReserva.CONFIRMACION_SOLICITADA
                    || siguiente == EstadoProcesoReserva.HOLD_VENCIDO;
            case CONFIRMACION_SOLICITADA -> siguiente == EstadoProcesoReserva.ESPERANDO_TELEFONO
                    || siguiente == EstadoProcesoReserva.CONFIRMACION_INCIERTA || siguiente == EstadoProcesoReserva.CONFIRMACION_RECHAZADA
                    || siguiente == EstadoProcesoReserva.HOLD_VENCIDO || siguiente == EstadoProcesoReserva.HOLD_NO_RECUPERABLE;
            case CONFIRMACION_INCIERTA -> siguiente == EstadoProcesoReserva.ESPERANDO_TELEFONO;
            case HOLD_RECHAZADO, ESPERANDO_TELEFONO, HOLD_VENCIDO, HOLD_NO_RECUPERABLE,
                    CONFIRMADA, CANCELADA, PROCESO_EXPIRADO, PROCESO_INVALIDO -> false;
        };
    }

    private static boolean esCierreKafka(EstadoProcesoReserva estado) {
        return estado == EstadoProcesoReserva.CONFIRMADA || estado == EstadoProcesoReserva.CANCELADA
                || estado == EstadoProcesoReserva.PROCESO_EXPIRADO || estado == EstadoProcesoReserva.PROCESO_INVALIDO;
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
        if (siguiente.resultadoOcurridoEn() != null && (estado != siguiente.estado()
                || !java.util.Objects.equals(resultadoOcurridoEn, siguiente.resultadoOcurridoEn()))) {
            return admiteResultado(siguiente);
        }
        if (!java.util.Objects.equals(reservaId, siguiente.reservaId())
                || !java.util.Objects.equals(resultadoOcurridoEn, siguiente.resultadoOcurridoEn())) { return false; }
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

    /** La solicitud canónica acredita aceptación inicial, nunca confirmación definitiva. */
    public ProcesoReserva recuperarConfirmacion(EventoInbox evidencia, Instant ahora) {
        if (!confirmacionPendienteDeEvidencia() || id == null || evidencia == null || evidencia.id() == null || evidencia.originalId() != null
                || evidencia.estado() != EstadoEventoReserva.SOLICITUD_REGISTRADA
                || !java.util.Objects.equals(id, evidencia.procesoLocalId())) { return this; }
        var evento = evidencia.evento();
        if (evento == null || !evento.esSolicitudInformacion()
                || !procesoExternoId.equals(evento.reservationProcessId()) || !holdId.equals(evento.holdId())
                || !vencimientoHold.isAfter(ahora) || !evento.solicitud().expiresAt().isAfter(ahora)) { return this; }
        Instant limite = evento.solicitud().expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!limite.isAfter(ahora)) { return this; }
        return registrarConfirmacion(limite, ahora.isBefore(actualizadoEn) ? actualizadoEn : ahora);
    }

    public boolean confirmacionPendienteDeEvidencia() {
        return estado == EstadoProcesoReserva.CONFIRMACION_SOLICITADA || estado == EstadoProcesoReserva.CONFIRMACION_INCIERTA;
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
    private boolean admiteResultado(ProcesoReserva siguiente) {
        if (!estado.requiereDatosHold() || !holdId.equals(siguiente.holdId())
                || !procesoExternoId.equals(siguiente.procesoExternoId()) || siguiente.vencimientoHold().isAfter(vencimientoHold)) { return false; }
        if (estado.resultadoDefinitivo()) {
            return estado == EstadoProcesoReserva.CONFIRMADA && siguiente.estado() == EstadoProcesoReserva.CANCELADA
                    && reservaId.equals(siguiente.reservaId()) && (resultadoOcurridoEn == null
                    || !siguiente.resultadoOcurridoEn().isBefore(resultadoOcurridoEn));
        }
        return siguiente.estado() == EstadoProcesoReserva.CONFIRMADA || siguiente.estado() == EstadoProcesoReserva.CANCELADA
                || siguiente.estado() == EstadoProcesoReserva.PROCESO_EXPIRADO || siguiente.estado() == EstadoProcesoReserva.PROCESO_INVALIDO
                || (siguiente.estado() == EstadoProcesoReserva.ESPERANDO_TELEFONO && (resultadoOcurridoEn == null
                    || siguiente.resultadoOcurridoEn().isAfter(resultadoOcurridoEn)));
    }

    public ProcesoReserva registrarResultadoEvento(ResultadoReserva resultado, Instant ocurrido, Instant ahora) {
        Instant limite = vencimientoHold;
        if (resultado.expiresAt() != null && resultado.expiresAt().isBefore(limite)) { limite = resultado.expiresAt(); }
        var siguiente = new ProcesoReserva(id, usuarioId, profesionalId, fecha, hora, resultado.estadoDestino(), holdId,
                procesoExternoId, limite, creadoEn, ahora.isBefore(actualizadoEn) ? actualizadoEn : ahora, version,
                resultado.reservationId(), ocurrido);
        if (!admiteResultado(siguiente)) { throw new IllegalStateException("Transición de resultado no permitida"); }
        return siguiente;
    }

    /** Aplica evidencia REST definitiva sin fabricar un evento Kafka ni reabrir estados finales. */
    public ProcesoReserva registrarCancelacionRest(Long reserva, Instant ocurrido, Instant ahora) {
        if (estado == EstadoProcesoReserva.CANCELADA && java.util.Objects.equals(reservaId, reserva)) { return this; }
        var siguiente = new ProcesoReserva(id, usuarioId, profesionalId, fecha, hora, EstadoProcesoReserva.CANCELADA,
                holdId, procesoExternoId, vencimientoHold, creadoEn,
                ahora.isBefore(actualizadoEn) ? actualizadoEn : ahora, version, reserva, ocurrido);
        if (!admiteResultado(siguiente)) { throw new IllegalStateException("Cancelacion incompatible con el proceso local"); }
        return siguiente;
    }

}
