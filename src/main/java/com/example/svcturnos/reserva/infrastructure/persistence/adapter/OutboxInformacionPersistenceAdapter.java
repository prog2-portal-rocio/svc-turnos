package com.example.svcturnos.reserva.infrastructure.persistence.adapter;

import com.example.svcturnos.reserva.domain.exception.EnviarInformacionException;
import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.example.svcturnos.reserva.domain.exception.EnviarInformacionException.Motivo.*;

@Repository
public class OutboxInformacionPersistenceAdapter implements OutboxInformacionPort {
    private static final RowMapper<EnvioInformacion> MAPPER = (rs, row) -> new EnvioInformacion(
            rs.getObject("event_id", UUID.class), rs.getLong("proceso_id"), rs.getLong("solicitud_id"),
            rs.getString("group_id"), rs.getString("topic"), rs.getString("proceso_externo_id"), rs.getString("hold_id"),
            rs.getString("request_event_id"), rs.getString("telefono") == null ? null : new Telefono(rs.getString("telefono")),
            rs.getTimestamp("ocurrido_en").toInstant(), rs.getTimestamp("vence_en").toInstant(),
            EstadoEnvioInformacion.valueOf(rs.getString("estado")), rs.getObject("intento_id", UUID.class));
    private final JdbcTemplate jdbc;
    private final ProcesoReservaPersistencePort procesos;
    private final InboxReservasPort inbox;
    private final Clock clock;
    public OutboxInformacionPersistenceAdapter(JdbcTemplate jdbc, ProcesoReservaPersistencePort procesos,
            InboxReservasPort inbox, Clock clock) {
        this.jdbc = jdbc; this.procesos = procesos; this.inbox = inbox; this.clock = clock;
    }

    @Override @Transactional
    public EnvioInformacion registrar(Long procesoId, Long propietario, Long solicitudId, Telefono telefono, DestinoInformacion destino) {
        // Serializa solicitudes equivalentes en PostgreSQL, también entre instancias del servicio.
        var ids = jdbc.queryForList("SELECT id FROM procesos_reserva WHERE id=? FOR UPDATE", Long.class, procesoId);
        if (ids.isEmpty()) { throw new EnviarInformacionException(PROCESO_INEXISTENTE); }
        var proceso = procesos.buscarPorId(procesoId).orElseThrow(() -> new EnviarInformacionException(PROCESO_INEXISTENTE));
        var solicitud = inbox.buscarPorId(solicitudId).orElseThrow(() -> new EnviarInformacionException(SOLICITUD_NO_DISPONIBLE));
        var ahora = clock.instant();
        var limite = SolicitudEnvioInformacion.validar(proceso, propietario, solicitud, ahora);
        var anterior = jdbc.query("SELECT * FROM informacion_adicional_outbox WHERE solicitud_id=?", MAPPER, solicitudId);
        if (!anterior.isEmpty()) {
            var existente = anterior.getFirst();
            // Sin el dato depurado no puede acreditarse que este número ya fue aceptado.
            if (existente.telefono() == null || !existente.telefono().equals(telefono)) {
                throw new EnviarInformacionException(RESPUESTA_EXISTENTE);
            }
            return existente;
        }
        var evento = solicitud.evento();
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO informacion_adicional_outbox(event_id,proceso_id,solicitud_id,group_id,topic,proceso_externo_id,
                    hold_id,request_event_id,telefono,ocurrido_en,vence_en,estado,proximo_intento)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDIENTE',?)
                """, id, procesoId, solicitudId, destino.groupId(), destino.topic(), evento.reservationProcessId(),
                evento.holdId(), evento.eventId(), telefono.numero(), Timestamp.from(ahora), Timestamp.from(limite), Timestamp.from(ahora));
        return jdbc.queryForObject("SELECT * FROM informacion_adicional_outbox WHERE event_id=?", MAPPER, id);
    }

    @Override @Transactional
    public Optional<EnvioInformacion> reclamar(Instant ahora, Duration concesion, int maxIntentos) {
        var filas = jdbc.query("""
                UPDATE informacion_adicional_outbox SET estado='PUBLICANDO', intentos=intentos+1, intento_id=?, concesion_hasta=?
                WHERE event_id IN (
                    SELECT event_id FROM informacion_adicional_outbox WHERE telefono IS NOT NULL AND vence_en>?
                        AND intentos<? AND ((estado IN ('PENDIENTE','INCIERTA') AND proximo_intento<=?)
                            OR (estado='PUBLICANDO' AND concesion_hasta<=?))
                    ORDER BY proximo_intento,event_id FOR UPDATE SKIP LOCKED LIMIT 1
                ) RETURNING *
                """, MAPPER, UUID.randomUUID(), Timestamp.from(ahora.plus(concesion)), Timestamp.from(ahora), maxIntentos,
                Timestamp.from(ahora), Timestamp.from(ahora));
        return filas.stream().findFirst();
    }

    @Override @Transactional
    public void registrarResultado(UUID eventoId, UUID intentoId, boolean enviada, Instant ahora, Duration demora) {
        jdbc.update("""
                UPDATE informacion_adicional_outbox SET estado=?, telefono=CASE WHEN ? THEN NULL ELSE telefono END,
                    proximo_intento=?, intento_id=NULL, concesion_hasta=NULL
                WHERE event_id=? AND intento_id=? AND estado='PUBLICANDO'
                """, enviada ? "ENVIADA" : "INCIERTA", enviada, Timestamp.from(ahora.plus(demora)), eventoId, intentoId);
    }

    @Override @Transactional
    public void depurarVencidos(Instant ahora) {
        jdbc.update("""
                UPDATE informacion_adicional_outbox SET estado='INCIERTA',intento_id=NULL,concesion_hasta=NULL
                WHERE estado='PUBLICANDO' AND concesion_hasta<=?
                """, Timestamp.from(ahora));
        jdbc.update("""
                UPDATE informacion_adicional_outbox SET telefono=NULL,
                    estado=CASE WHEN intentos=0 THEN 'VENCIDA' ELSE 'INCIERTA' END, intento_id=NULL, concesion_hasta=NULL
                WHERE vence_en<=? AND telefono IS NOT NULL AND (estado<>'PUBLICANDO' OR concesion_hasta<=?)
                """, Timestamp.from(ahora), Timestamp.from(ahora));
    }
}
