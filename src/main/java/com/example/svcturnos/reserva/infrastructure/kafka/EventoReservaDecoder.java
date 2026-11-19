package com.example.svcturnos.reserva.infrastructure.kafka;

import com.example.svcturnos.reserva.domain.model.*;
import com.example.svcturnos.reserva.infrastructure.kafka.dto.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.time.Instant;
import static com.example.svcturnos.reserva.domain.model.RecepcionEventoReserva.MotivoInvalido.*;

@Component
public class EventoReservaDecoder {
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final EventoReservaMapper mapper = new EventoReservaMapper();

    public RecepcionEventoReserva decodificar(MensajeReserva mensaje) {
        JsonNode contenido;
        try {
            contenido = mensaje.contenido() == null ? null : json.readTree(mensaje.contenido());
        } catch (JacksonException _) { return invalido(mensaje, null, JSON_INVALIDO); }
        if (contenido == null || !contenido.isObject()) { return invalido(mensaje, null, JSON_INVALIDO); }
        String original = json.writeValueAsString(contenido);
        var version = contenido.path("schemaVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
            return invalido(mensaje, original, ESQUEMA_NO_SOPORTADO);
        }
        try {
            var dto = new EventoReservaDto(texto(contenido, "eventId"), texto(contenido, "eventType"),
                    texto(contenido, "reservationProcessId"), texto(contenido, "holdId"),
                    Instant.parse(texto(contenido, "occurredAt")), version.intValue());
            SolicitudInformacionDto solicitud = null;
            if (EventoReserva.TIPO_SOLICITUD_INFORMACION.equals(dto.eventType())) {
                solicitud = new SolicitudInformacionDto(texto(contenido, "requiredField"), texto(contenido, "message"),
                        Instant.parse(texto(contenido, "expiresAt")));
            }
            var evento = mapper.toDomain(dto, solicitud);
            if (!evento.reservationProcessId().equals(clave(mensaje.clave()))) {
                return invalido(mensaje, original, CLAVE_INCONSISTENTE);
            }
            return new RecepcionEventoReserva(mensaje, evento, original, null);
        } catch (IllegalArgumentException | java.time.DateTimeException _) { return invalido(mensaje, original, DATOS_INVALIDOS); }
    }

    private String texto(JsonNode node, String campo) {
        var valor = node.path(campo);
        if (!valor.isString() || valor.asString().isBlank() || valor.asString().indexOf(0) >= 0) { throw new IllegalArgumentException("Campo obligatorio inválido"); }
        return valor.asString();
    }

    private String clave(byte[] bytes) {
        if (bytes == null) { return null; }
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException _) { return null; }
    }

    private RecepcionEventoReserva invalido(MensajeReserva mensaje, String original, RecepcionEventoReserva.MotivoInvalido motivo) {
        return new RecepcionEventoReserva(mensaje, null, original, motivo);
    }
}
