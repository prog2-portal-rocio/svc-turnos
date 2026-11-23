package com.example.svcturnos.reserva.infrastructure.persistence.adapter;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import static com.example.svcturnos.reserva.domain.model.EstadoEventoReserva.*;

@Repository
public class AplicarResultadoReservaPersistenceAdapter implements AplicarResultadoReservaPort {
    private final JdbcTemplate jdbc;
    private final InboxReservasPort inbox;
    private final ProcesoReservaPersistencePort procesos;
    private final CancelacionReservaPersistencePort cancelaciones;
    private final Clock clock;
    private final String grupo;
    public AplicarResultadoReservaPersistenceAdapter(JdbcTemplate jdbc, InboxReservasPort inbox,
            ProcesoReservaPersistencePort procesos, CancelacionReservaPersistencePort cancelaciones, Clock clock, @Value("${turnos.kafka.integration-group-id:}") String grupo) {
        this.cancelaciones = cancelaciones;
        this.jdbc = jdbc; this.inbox = inbox; this.procesos = procesos; this.clock = clock; this.grupo = grupo;
    }

    @Override @Transactional
    public EventoInbox aplicar(Long id) {
        var recibido = inbox.buscarPorId(id).orElseThrow(() -> new IllegalArgumentException("Recepción inexistente"));
        if (!pendiente(recibido)) { return recibido; }
        var candidatos = jdbc.queryForList("SELECT id FROM procesos_reserva WHERE proceso_externo_id=? ORDER BY id",
                Long.class, recibido.evento().reservationProcessId());
        if (candidatos.size() != 1) {
            return marcar(id, candidatos.isEmpty() ? PENDIENTE_CORRELACION : CORRELACION_INCONSISTENTE, null);
        }
        Long procesoId = candidatos.getFirst();
        // Mismo orden de bloqueo que el alta de la outbox. No se realizan llamadas remotas en esta transacción.
        jdbc.queryForList("SELECT id FROM procesos_reserva WHERE id=? FOR UPDATE", Long.class, procesoId);
        jdbc.queryForList("SELECT id FROM eventos_reserva_inbox WHERE id=? FOR UPDATE", Long.class, id);
        recibido = inbox.buscarPorId(id).orElseThrow();
        if (!pendiente(recibido)) { return recibido; }
        var proceso = procesos.buscarPorId(procesoId).orElseThrow();
        return aplicarBloqueado(recibido, proceso);
    }

    private EventoInbox aplicarBloqueado(EventoInbox recibido, ProcesoReserva proceso) {
        var evento = recibido.evento();
        var resultado = evento.resultado();
        if (resultado == null || !grupo.equals(resultado.groupId())) {
            return marcar(recibido.id(), grupo.isBlank() ? PENDIENTE_CORRELACION : CORRELACION_INCONSISTENTE, proceso.id());
        }
        var decision = PoliticaResultadosReserva.evaluar(proceso, evento);
        if (decision == CORRELACION_INCONSISTENTE) { return marcar(recibido.id(), decision, proceso.id()); }
        var solicitud = solicitudCorrespondiente(evento, proceso);
        if (solicitud.estado() != SOLICITUD_REGISTRADA) { return marcar(recibido.id(), solicitud.estado(), proceso.id()); }
        if (decision != RESULTADO_APLICADO) {
            if (decision == RESULTADO_OBSOLETO && resultado.tipo() == TipoResultadoReserva.CANCELACION) {
                cancelaciones.confirmarDesdeEvento(proceso, resultado.reservationId(), evento.occurredAt());
            }
            return marcar(recibido.id(), decision, proceso.id());
        }
        if (reservaAsignadaAOtroProceso(resultado.reservationId(), proceso.id())) {
            return marcar(recibido.id(), CORRELACION_INCONSISTENTE, proceso.id());
        }
        if (resultado.tipo() == TipoResultadoReserva.RECHAZO_TELEFONO && !hayRespuestaRechazable(solicitud.id(), evento)) {
            return marcar(recibido.id(), PENDIENTE_CORRELACION, proceso.id());
        }
        var actualizado = procesos.guardar(proceso.registrarResultadoEvento(resultado, evento.occurredAt(), clock.instant()));
        if (resultado.tipo() == TipoResultadoReserva.CANCELACION) {
            cancelaciones.confirmarDesdeEvento(actualizado, resultado.reservationId(), evento.occurredAt());
        }
        deshabilitarRespuestas(proceso.id(), recibido.id(), resultado.tipo() == TipoResultadoReserva.RECHAZO_TELEFONO ? solicitud.id() : null);
        return marcar(recibido.id(), RESULTADO_APLICADO, proceso.id());
    }

    private boolean reservaAsignadaAOtroProceso(Long reserva, Long proceso) {
        return reserva != null && !jdbc.queryForList("SELECT id FROM procesos_reserva WHERE reserva_id=? AND id<>?",
                Long.class, reserva, proceso).isEmpty();
    }

    private record ReferenciaSolicitud(Long id, EstadoEventoReserva estado) { }

    private ReferenciaSolicitud solicitudCorrespondiente(EventoReserva evento, ProcesoReserva proceso) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM eventos_reserva_inbox WHERE canonico AND event_id=?",
                Long.class, evento.resultado().requestEventId());
        if (ids.isEmpty()) { return new ReferenciaSolicitud(null, PENDIENTE_CORRELACION); }
        if (ids.size() != 1) { return new ReferenciaSolicitud(null, CORRELACION_INCONSISTENTE); }
        var solicitud = inbox.buscarPorId(ids.getFirst()).orElseThrow();
        if (!solicitud.evento().esSolicitudInformacion() || !proceso.procesoExternoId().equals(solicitud.evento().reservationProcessId())
                || !proceso.holdId().equals(solicitud.evento().holdId())) {
            return new ReferenciaSolicitud(null, CORRELACION_INCONSISTENTE);
        }
        if (solicitud.pendienteDeCorrelacion()) {
            solicitud = inbox.registrarCorrelacion(solicitud.id(), solicitud.evento().correlacionar(List.of(proceso)));
        }
        boolean correcta = solicitud.estado() == SOLICITUD_REGISTRADA && proceso.id().equals(solicitud.procesoLocalId());
        return new ReferenciaSolicitud(solicitud.id(), correcta ? SOLICITUD_REGISTRADA : CORRELACION_INCONSISTENTE);
    }

    private boolean hayRespuestaRechazable(Long solicitud, EventoReserva evento) {
        var ids = jdbc.queryForList("""
                SELECT event_id FROM informacion_adicional_outbox WHERE solicitud_id=? AND habilitada AND intentos>0
                    AND group_id=? AND ocurrido_en<=? FOR UPDATE
                """, java.util.UUID.class, solicitud, grupo, Timestamp.from(evento.occurredAt()));
        return ids.size() == 1;
    }

    private void deshabilitarRespuestas(Long proceso, Long resultado, Long solicitud) {
        jdbc.update("""
                UPDATE informacion_adicional_outbox SET habilitada=false, resultado_inbox_id=?, telefono=NULL,
                    intento_id=NULL, concesion_hasta=NULL,
                    estado=CASE WHEN estado='ENVIADA' THEN estado WHEN intentos=0 THEN 'VENCIDA' ELSE 'INCIERTA' END
                WHERE proceso_id=? AND habilitada AND (CAST(? AS BIGINT) IS NULL OR solicitud_id=?)
                """, resultado, proceso, solicitud, solicitud);
    }

    private boolean pendiente(EventoInbox evento) {
        return evento.evento() != null && evento.evento().esResultado()
                && (evento.pendienteDeCorrelacion() || evento.estado() == PENDIENTE_TIPO || evento.estado() == REQUIERE_RECONCILIACION);
    }

    private EventoInbox marcar(Long id, EstadoEventoReserva estado, Long proceso) {
        jdbc.update("""
                UPDATE eventos_reserva_inbox SET estado=?,proceso_local_id=?
                WHERE id=? AND canonico AND estado IN ('RECIBIDO','PENDIENTE_CORRELACION','PENDIENTE_TIPO','REQUIERE_RECONCILIACION')
                """, estado.name(), proceso, id);
        return inbox.buscarPorId(id).orElseThrow();
    }
}
