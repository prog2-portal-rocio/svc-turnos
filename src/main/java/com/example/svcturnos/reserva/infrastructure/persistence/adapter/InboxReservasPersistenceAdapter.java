package com.example.svcturnos.reserva.infrastructure.persistence.adapter;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.domain.ports.out.InboxReservasPort;
import com.example.svcturnos.reserva.infrastructure.persistence.mapper.EventoInboxRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Transactional(readOnly = true)
public class InboxReservasPersistenceAdapter implements InboxReservasPort {
    private static final String INSERTAR = """
            INSERT INTO eventos_reserva_inbox(topic,particion,offset_mensaje,clave,contenido,payload_json,event_id,event_id_hash,event_type,
                proceso_externo_id,hold_id,occurred_at,schema_version,required_field,mensaje,expires_at,canonico,estado,motivo,original_id)
            VALUES (:topic,:particion,:offset,:clave,:contenido,:json,:eventId,:eventIdHash,:tipo,:externo,:hold,
                :ocurrido,:version,:campo,:mensaje,:vencimiento,:canonico,:estado,:motivo,:original)
            ON CONFLICT DO NOTHING RETURNING id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final EventoInboxRowMapper mapper = new EventoInboxRowMapper();
    private final JsonMapper json = JsonMapper.builder().build();

    public InboxReservasPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    @Override
    @Transactional
    public EventoInbox conservar(RecepcionEventoReserva recepcion) {
        var parametros = parametros(recepcion);
        var insertados = jdbc.queryForList(INSERTAR, parametros, Long.class);
        if (!insertados.isEmpty()) { return buscarPorId(insertados.getFirst()).orElseThrow(); }
        var mismaPosicion = jdbc.query("""
                SELECT * FROM eventos_reserva_inbox WHERE topic=:topic AND particion=:particion AND offset_mensaje=:offset
                """, parametros, mapper);
        if (!mismaPosicion.isEmpty()) { return mismaPosicion.getFirst(); }
        var original = jdbc.queryForObject("SELECT id FROM eventos_reserva_inbox WHERE canonico AND event_id=:eventId", parametros, Long.class);
        parametros.addValue("original", original);
        String contenidoOriginal = jdbc.queryForObject("SELECT payload_json FROM eventos_reserva_inbox WHERE id=:original", parametros, String.class);
        boolean iguales = json.readTree(contenidoOriginal).equals(json.readTree(recepcion.json()));
        parametros.addValue("canonico", false).addValue("estado",
                iguales ? EstadoEventoReserva.DUPLICADO.name() : EstadoEventoReserva.EVENT_ID_EN_CONFLICTO.name());
        jdbc.queryForList(INSERTAR, parametros, Long.class);
        return jdbc.queryForObject("""
                SELECT * FROM eventos_reserva_inbox WHERE topic=:topic AND particion=:particion AND offset_mensaje=:offset
                """, parametros, mapper);
    }

    private MapSqlParameterSource parametros(RecepcionEventoReserva recepcion) {
        var mensaje = recepcion.mensaje();
        var evento = recepcion.evento();
        var solicitud = evento == null ? null : evento.solicitud();
        return new MapSqlParameterSource().addValue("topic", mensaje.topic()).addValue("particion", mensaje.particion())
                .addValue("offset", mensaje.offset()).addValue("clave", mensaje.clave(), Types.BINARY)
                .addValue("contenido", mensaje.contenido(), Types.BINARY).addValue("json", recepcion.json())
                .addValue("eventId", evento == null ? null : evento.eventId())
                .addValue("eventIdHash", evento == null ? null : huella(evento.eventId()), Types.BINARY).addValue("tipo", evento == null ? null : evento.eventType())
                .addValue("externo", evento == null ? null : evento.reservationProcessId()).addValue("hold", evento == null ? null : evento.holdId())
                .addValue("ocurrido", evento == null ? null : Timestamp.from(evento.occurredAt()))
                .addValue("version", evento == null ? null : evento.schemaVersion())
                .addValue("campo", solicitud == null ? null : solicitud.requiredField()).addValue("mensaje", solicitud == null ? null : solicitud.message())
                .addValue("vencimiento", solicitud == null ? null : Timestamp.from(solicitud.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
                .addValue("canonico", evento != null).addValue("estado", evento == null ? EstadoEventoReserva.INVALIDO.name() : EstadoEventoReserva.RECIBIDO.name())
                .addValue("motivo", recepcion.invalidez() == null ? null : recepcion.invalidez().name()).addValue("original", null);
    }

    private byte[] huella(String eventId) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(eventId.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException _) {
            throw new IllegalStateException("SHA-256 no disponible en el entorno Java");
        }
    }

    @Override
    public Optional<MensajeReserva> buscarMensajePorId(Long id) {
        return jdbc.query("SELECT topic,particion,offset_mensaje,clave,contenido FROM eventos_reserva_inbox WHERE id=:id",
                new MapSqlParameterSource("id", id), (rs, row) -> new MensajeReserva(rs.getString("topic"), rs.getInt("particion"),
                        rs.getLong("offset_mensaje"), rs.getBytes("clave"), rs.getBytes("contenido"))).stream().findFirst();
    }

    @Override
    public Optional<EventoInbox> buscarPorId(Long id) {
        return jdbc.query("SELECT * FROM eventos_reserva_inbox WHERE id=:id", new MapSqlParameterSource("id", id), mapper).stream().findFirst();
    }

    @Override
    public List<Long> buscarPendientes(int limite) {
        if (limite < 1 || limite > 1000) { throw new IllegalArgumentException("Límite de consulta entre 1 y 1000"); }
        return jdbc.queryForList("""
                SELECT id FROM eventos_reserva_inbox WHERE estado IN ('RECIBIDO','PENDIENTE_CORRELACION') ORDER BY id LIMIT :limite
                """, new MapSqlParameterSource("limite", limite), Long.class);
    }

    @Override
    @Transactional
    public EventoInbox registrarCorrelacion(Long id, CorrelacionEvento correlacion) {
        jdbc.update("""
                UPDATE eventos_reserva_inbox SET estado=:estado, proceso_local_id=:proceso
                WHERE id=:id AND canonico AND estado IN ('RECIBIDO','PENDIENTE_CORRELACION')
                """, new MapSqlParameterSource("id", id).addValue("estado", correlacion.estado().name()).addValue("proceso", correlacion.procesoLocalId()));
        return buscarPorId(id).orElseThrow(() -> new IllegalArgumentException("Recepción no encontrada"));
    }
}
