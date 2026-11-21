package com.example.svcturnos.reserva.infrastructure.persistence.adapter;

import com.example.svcturnos.reserva.domain.exception.CancelarReservaException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import static com.example.svcturnos.reserva.domain.exception.CancelarReservaException.Motivo.*;

@Repository
public class CancelacionReservaPersistenceAdapter implements CancelacionReservaPersistencePort {
    private final JdbcTemplate jdbc;
    private final ProcesoReservaPersistencePort procesos;
    private final Clock clock;
    private static final RowMapper<CancelacionReserva> MAPPER = (row, numero) -> new CancelacionReserva(
            row.getString("proceso_externo_id"), row.getLong("usuario_id"), row.getLong("reserva_id"),
            EstadoCancelacion.valueOf(row.getString("estado")), row.getTimestamp("solicitada_en").toInstant(),
            row.getTimestamp("actualizada_en").toInstant(), row.getTimestamp("resultado_en") == null ? null
            : row.getTimestamp("resultado_en").toInstant(), row.getBoolean("requiere_reconciliacion"));
    public CancelacionReservaPersistenceAdapter(JdbcTemplate jdbc, ProcesoReservaPersistencePort procesos, Clock clock) {
        this.jdbc = jdbc; this.procesos = procesos; this.clock = clock;
    }
    @Override public Optional<CancelacionReserva> buscar(String id) {
        return jdbc.query("SELECT * FROM cancelaciones_reserva WHERE proceso_externo_id=?", MAPPER, id).stream().findFirst();
    }
    @Override @Transactional
    public PreparacionCancelacion preparar(Long propietario, ReservaCentral reserva) {
        if (!PacienteReserva.identificadorExterno(propietario).equals(reserva.externalPatientId())) {
            throw new CancelarReservaException(RESERVA_NO_ENCONTRADA);
        }
        var local = bloquearProceso(reserva.reservationProcessId(), propietario, reserva.reservationId());
        if (local != null && (!local.profesionalId().equals(reserva.professionalId()) || !local.fecha().equals(reserva.appointmentDate())
                || !local.hora().equals(reserva.startTime()))) { throw new CancelarReservaException(CORRELACION_INCONSISTENTE); }
        var ahora = Timestamp.from(clock.instant());
        int insertado = jdbc.update("""
                INSERT INTO cancelaciones_reserva(proceso_externo_id,usuario_id,reserva_id,estado,solicitada_en,actualizada_en)
                VALUES (?,?,?,'SOLICITADA',?,?) ON CONFLICT(proceso_externo_id) DO NOTHING
                """, reserva.reservationProcessId(), propietario, reserva.reservationId(), ahora, ahora);
        var intento = bloquearIntento(reserva.reservationProcessId(), propietario, reserva.reservationId());
        if (intento.estado() == EstadoCancelacion.CANCELADA) { return new PreparacionCancelacion(intento, false); }
        if (reserva.status() == EstadoReservaCentral.CANCELLED) {
            return new PreparacionCancelacion(finalizar(intento, local, reserva.cancelledAt()), false);
        }
        if (local != null && local.estado() == EstadoProcesoReserva.CANCELADA) {
            return new PreparacionCancelacion(finalizar(intento, local, local.resultadoOcurridoEn()), false);
        }
        if (local != null && local.estado() != EstadoProcesoReserva.CONFIRMADA) {
            throw new CancelarReservaException(CORRELACION_INCONSISTENTE);
        }
        if (intento.estado() == EstadoCancelacion.RECHAZADA) {
            jdbc.update("UPDATE cancelaciones_reserva SET estado='SOLICITADA',actualizada_en=GREATEST(actualizada_en,?) WHERE proceso_externo_id=?",
                    ahora, intento.procesoExternoId());
            return new PreparacionCancelacion(buscar(intento.procesoExternoId()).orElseThrow(), true);
        }
        return new PreparacionCancelacion(intento, insertado == 1);
    }
    @Override @Transactional
    public CancelacionReserva confirmar(Long propietario, CancelacionConfirmada resultado) {
        var local = bloquearProceso(resultado.reservationProcessId(), propietario, resultado.reservationId());
        var intento = bloquearIntento(resultado.reservationProcessId(), propietario, resultado.reservationId());
        return intento.estado() == EstadoCancelacion.CANCELADA ? intento : finalizar(intento, local, resultado.resultadoEn());
    }
    private ProcesoReserva bloquearProceso(String id, Long propietario, Long reserva) {
        var ids = jdbc.queryForList("SELECT id FROM procesos_reserva WHERE proceso_externo_id=? ORDER BY id FOR UPDATE", Long.class, id);
        if (ids.size() > 1) { throw new CancelarReservaException(CORRELACION_INCONSISTENTE); }
        if (ids.isEmpty()) { return null; }
        var local = procesos.buscarPorId(ids.getFirst()).orElseThrow();
        if (!propietario.equals(local.usuarioId())) { throw new CancelarReservaException(RESERVA_NO_ENCONTRADA); }
        if (local.reservaId() != null && !reserva.equals(local.reservaId())) { throw new CancelarReservaException(CORRELACION_INCONSISTENTE); }
        return local;
    }
    private CancelacionReserva bloquearIntento(String id, Long propietario, Long reserva) {
        var intento = jdbc.query("SELECT * FROM cancelaciones_reserva WHERE proceso_externo_id=? FOR UPDATE", MAPPER, id)
                .stream().findFirst().orElseThrow(() -> new CancelarReservaException(CORRELACION_INCONSISTENTE));
        if (!propietario.equals(intento.usuarioId())) { throw new CancelarReservaException(RESERVA_NO_ENCONTRADA); }
        if (!reserva.equals(intento.reservaId())) { throw new CancelarReservaException(CORRELACION_INCONSISTENTE); }
        return intento;
    }
    private CancelacionReserva finalizar(CancelacionReserva intento, ProcesoReserva local, Instant ocurrido) {
        boolean reconciliar = false;
        if (local != null) {
            reconciliar = aplicarLocal(local, intento.reservaId(), ocurrido);
            jdbc.update("""
                    UPDATE informacion_adicional_outbox SET habilitada=false,telefono=NULL,intento_id=NULL,concesion_hasta=NULL,
                      estado=CASE WHEN estado='ENVIADA' THEN estado WHEN intentos=0 THEN 'VENCIDA' ELSE 'INCIERTA' END
                    WHERE proceso_id=? AND habilitada
                    """, local.id());
        }
        jdbc.update("""
                UPDATE cancelaciones_reserva SET estado='CANCELADA',resultado_en=?,requiere_reconciliacion=?,
                  actualizada_en=GREATEST(actualizada_en,?) WHERE proceso_externo_id=?
                """, ocurrido == null ? null : Timestamp.from(ocurrido), reconciliar, Timestamp.from(clock.instant()), intento.procesoExternoId());
        return buscar(intento.procesoExternoId()).orElseThrow();
    }
    private boolean aplicarLocal(ProcesoReserva local, Long reserva, Instant ocurrido) {
        if (local.estado() == EstadoProcesoReserva.CANCELADA) { return false; }
        if (ocurrido == null) { return true; }
        ProcesoReserva siguiente;
        try { siguiente = local.registrarCancelacionRest(reserva, ocurrido, clock.instant()); }
        catch (IllegalArgumentException | IllegalStateException _) { return true; }
        procesos.guardar(siguiente);
        return false;
    }
    @Override @Transactional
    public CancelacionReserva registrarFallo(Long propietario, String id, EstadoCancelacion estado) {
        if (estado != EstadoCancelacion.RECHAZADA && estado != EstadoCancelacion.INCIERTA) { throw new IllegalArgumentException("Estado de fallo invalido"); }
        jdbc.update("""
                UPDATE cancelaciones_reserva SET estado=?,actualizada_en=GREATEST(actualizada_en,?)
                WHERE proceso_externo_id=? AND usuario_id=? AND estado='SOLICITADA'
                """, estado.name(), Timestamp.from(clock.instant()), id, propietario);
        var intento = buscar(id).orElseThrow();
        if (!propietario.equals(intento.usuarioId())) { throw new CancelarReservaException(RESERVA_NO_ENCONTRADA); }
        return intento;
    }
    @Override @Transactional
    public void confirmarDesdeEvento(ProcesoReserva proceso, Long reserva, Instant ocurrido) {
        if (proceso.estado() != EstadoProcesoReserva.CANCELADA || !reserva.equals(proceso.reservaId())) { return; }
        jdbc.update("""
                UPDATE cancelaciones_reserva SET estado='CANCELADA',resultado_en=COALESCE(resultado_en,?),
                  requiere_reconciliacion=false,actualizada_en=GREATEST(actualizada_en,?)
                WHERE proceso_externo_id=? AND usuario_id=? AND reserva_id=?
                """, Timestamp.from(ocurrido), Timestamp.from(clock.instant()), proceso.procesoExternoId(), proceso.usuarioId(), reserva);
    }
}
