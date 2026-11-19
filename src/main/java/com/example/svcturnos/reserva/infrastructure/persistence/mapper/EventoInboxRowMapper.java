package com.example.svcturnos.reserva.infrastructure.persistence.mapper;

import com.example.svcturnos.reserva.domain.model.*;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

public class EventoInboxRowMapper implements RowMapper<EventoInbox> {
    @Override
    public EventoInbox mapRow(ResultSet rs, int rowNum) throws SQLException {
        EventoReserva evento = null;
        if (rs.getString("event_id") != null) {
            SolicitudInformacionAdicional solicitud = null;
            if (EventoReserva.TIPO_SOLICITUD_INFORMACION.equals(rs.getString("event_type"))) {
                solicitud = new SolicitudInformacionAdicional(rs.getString("required_field"), rs.getString("mensaje"), instante(rs, "expires_at"));
            }
            evento = new EventoReserva(rs.getString("event_id"), rs.getString("event_type"), rs.getString("proceso_externo_id"),
                    rs.getString("hold_id"), instante(rs, "occurred_at"), rs.getInt("schema_version"), solicitud);
        }
        return new EventoInbox(rs.getLong("id"), evento, EstadoEventoReserva.valueOf(rs.getString("estado")),
                rs.getObject("proceso_local_id", Long.class), rs.getObject("original_id", Long.class));
    }

    private Instant instante(ResultSet rs, String campo) throws SQLException {
        Timestamp valor = rs.getTimestamp(campo);
        return valor == null ? null : valor.toInstant();
    }
}
