package com.example.svcturnos.reserva.infrastructure.persistence.mapper;

import com.example.svcturnos.reserva.domain.model.*;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import com.example.svcturnos.reserva.infrastructure.kafka.ResultadoReservaJsonMapper;
import tools.jackson.databind.json.JsonMapper;

public class EventoInboxRowMapper implements RowMapper<EventoInbox> {
    private final JsonMapper json = JsonMapper.builder().build();
    @Override
    public EventoInbox mapRow(ResultSet rs, int rowNum) throws SQLException {
        EventoReserva evento = null;
        if (rs.getString("event_id") != null) {
            SolicitudInformacionAdicional solicitud = null;
            if (EventoReserva.TIPO_SOLICITUD_INFORMACION.equals(rs.getString("event_type"))) {
                solicitud = new SolicitudInformacionAdicional(rs.getString("required_field"), rs.getString("mensaje"), instante(rs, "expires_at"));
            }
            var resultado = resultado(rs);
            evento = new EventoReserva(rs.getString("event_id"), rs.getString("event_type"), rs.getString("proceso_externo_id"),
                    rs.getString("hold_id"), resultado == null ? instante(rs, "occurred_at") : resultado.ocurrido(),
                    rs.getInt("schema_version"), solicitud, resultado == null ? null : resultado.datos());
        }
        return new EventoInbox(rs.getLong("id"), evento, EstadoEventoReserva.valueOf(rs.getString("estado")),
                rs.getObject("proceso_local_id", Long.class), rs.getObject("original_id", Long.class));
    }

    private record ResultadoLeido(ResultadoReserva datos, Instant ocurrido) { }

    private ResultadoLeido resultado(ResultSet rs) throws SQLException {
        var tipo = TipoResultadoReserva.deEvento(rs.getString("event_type"));
        if (tipo.isEmpty()) { return null; }
        try {
            var contenido = json.readTree(rs.getString("payload_json"));
            // TIMESTAMPTZ redondea a microsegundos; el JSON conserva el instante original para el dominio.
            return new ResultadoLeido(ResultadoReservaJsonMapper.leer(tipo.get(), contenido),
                    Instant.parse(contenido.path("occurredAt").asString()));
        }
        catch (RuntimeException _) { return null; } // Recepciones históricas incompletas quedan para revisión, no se pierden.
    }

    private Instant instante(ResultSet rs, String campo) throws SQLException {
        Timestamp valor = rs.getTimestamp(campo);
        return valor == null ? null : valor.toInstant();
    }
}
